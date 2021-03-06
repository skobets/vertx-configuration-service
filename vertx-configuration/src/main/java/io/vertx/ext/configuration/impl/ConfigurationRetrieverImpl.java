package io.vertx.ext.configuration.impl;


import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.configuration.*;
import io.vertx.ext.configuration.spi.ConfigurationProcessor;
import io.vertx.ext.configuration.spi.ConfigurationStore;
import io.vertx.ext.configuration.spi.ConfigurationStoreFactory;
import io.vertx.ext.configuration.utils.Processors;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of the {@link ConfigurationRetriever}.
 *
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class ConfigurationRetrieverImpl implements ConfigurationRetriever {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationRetrieverImpl.class);

  private final Vertx vertx;
  private final List<ConfigurationProvider> providers;
  private final long scan;
  private final List<Handler<ConfigurationChange>> listeners = new ArrayList<>();
  private final ConfigStreamImpl streamOfConfiguration = new ConfigStreamImpl();

  private JsonObject current = new JsonObject();

  public ConfigurationRetrieverImpl(Vertx vertx, ConfigurationRetrieverOptions options) {
    this.vertx = vertx;

    ServiceLoader<ConfigurationStoreFactory> storeImpl =
        ServiceLoader.load(ConfigurationStoreFactory.class,
            ConfigurationStoreFactory.class.getClassLoader());

    Map<String, ConfigurationStoreFactory> nameToImplMap = new HashMap<>();
    storeImpl.iterator().forEachRemaining(factory -> nameToImplMap.put(factory.name(), factory));
    if (nameToImplMap.isEmpty()) {
      throw new IllegalStateException("No configuration store implementations found on the classpath");
    }

    // Iterate over the configured `stores` to configuration the stores
    providers = new ArrayList<>();
    for (ConfigurationStoreOptions option : options.getStores()) {
      String type = option.getType();
      if (type == null) {
        throw new IllegalArgumentException(
            "the `type` entry is mandatory in a configuration store configuration");
      }

      ConfigurationStoreFactory factory = nameToImplMap.get(type);
      if (factory == null) {
        throw new IllegalArgumentException("unknown configuration store implementation: " +
            type + " (known implementations are: " + nameToImplMap.keySet() + ")");
      }

      JsonObject config = option.getConfig();
      if (config == null) {
        config = new JsonObject();
      }
      ConfigurationStore store = factory.create(vertx, config);

      String format = option.getFormat() != null ? option.getFormat() : "json";
      ConfigurationProcessor processor = Processors.get(format);
      if (processor == null) {
        throw new IllegalArgumentException("unknown configuration format: " + format + " (supported formats are: " +
            Processors.getSupportedFormats());
      }
      providers.add(new ConfigurationProvider(store, processor, option.getConfig()));
    }

    if (options.getScanPeriod() > 0) {
      this.scan = vertx.setPeriodic(options.getScanPeriod(), l -> scan());
    } else {
      this.scan = -1;
    }

    getConfiguration(x -> {
      // Ignored.
    });
  }

  @Override
  public void getConfiguration(Handler<AsyncResult<JsonObject>> completionHandler) {
    Objects.requireNonNull(completionHandler);
    compute(ar -> {
      if (ar.succeeded()) {
        synchronized ((ConfigurationRetrieverImpl.this)) {
          current = ar.result();
          streamOfConfiguration.handle(current);
        }
      }
      completionHandler.handle(ar);
    });
  }

  @Override
  public Future<JsonObject> getConfigurationFuture() {
    Future<JsonObject> future = Future.future();
    getConfiguration(future.completer());
    return future;
  }

  @Override
  public void close() {
    if (scan != -1) {
      vertx.cancelTimer(scan);
    }

    streamOfConfiguration.close();

    for (ConfigurationProvider provider : providers) {
      provider.close(v -> {
      });
    }
  }

  @Override
  public synchronized JsonObject getCachedConfiguration() {
    return current.copy();
  }

  @Override
  public void listen(Handler<ConfigurationChange> listener) {
    Objects.requireNonNull(listener);
    listeners.add(listener);
  }

  @Override
  public ConfigurationStream configurationStream() {
    return streamOfConfiguration;
  }

  private void scan() {
    compute(ar -> {
      if (ar.failed()) {
        streamOfConfiguration.fail(ar.cause());
        LOGGER.error("Error while scanning configuration", ar.cause());
      } else {
        synchronized (ConfigurationRetrieverImpl.this) {
          // Check for changes
          if (!current.equals(ar.result())) {
            JsonObject prev = current;
            current = ar.result();
            listeners.forEach(l -> l.handle(new ConfigurationChange(prev, current)));
            streamOfConfiguration.handle(current);
          }
        }
      }
    });
  }

  private void compute(Handler<AsyncResult<JsonObject>> completionHandler) {
    List<Future> futures = providers.stream()
        .map(s -> {
          Future<JsonObject> conf = Future.future();
          s.get(vertx, ar -> {
            if (ar.succeeded()) {
              conf.complete(ar.result());
            } else {
              conf.fail(ar.cause());
            }
          });
          return conf;
        })
        .collect(Collectors.toList());

    CompositeFuture.all(futures).setHandler(r -> {
      if (r.failed()) {
        completionHandler.handle(Future.failedFuture(r.cause()));
      } else {
        // Merge the different futures
        JsonObject json = new JsonObject();
        futures.forEach(future -> json.mergeIn((JsonObject) future.result()));
        completionHandler.handle(Future.succeededFuture(json));
      }
    });
  }

  private class ConfigStreamImpl implements ConfigurationStream {

    private Handler<JsonObject> handler;
    private Handler<Throwable> exceptionHandler;
    private Handler<Void> endHandler;

    private JsonObject last;
    private boolean paused = false;

    @Override
    public synchronized ConfigurationStream exceptionHandler(Handler<Throwable> handler) {
      Objects.requireNonNull(handler);
      this.exceptionHandler = handler;
      return this;
    }

    @Override
    public ConfigurationStream handler(Handler<JsonObject> handler) {
      Objects.requireNonNull(handler);
      JsonObject conf;
      synchronized (this) {
        this.handler = handler;
        conf = getCachedConfiguration();
      }

      if (conf != null && !conf.isEmpty()) {
        this.handler.handle(conf);
      }

      return this;
    }

    @Override
    public synchronized ConfigurationStream pause() {
      paused = true;
      return this;
    }

    @Override
    public ConfigurationStream resume() {
      JsonObject conf = null;
      Handler<JsonObject> succ;
      synchronized (this) {
        paused = false;
        if (last != null) {
          conf = last;
          last = null;
        }
        succ = this.handler;
      }

      if (conf != null && succ != null) {
        succ.handle(conf);
      }

      return this;
    }

    @Override
    public synchronized ConfigurationStream endHandler(Handler<Void> endHandler) {
      Objects.requireNonNull(endHandler);
      this.endHandler = endHandler;
      return this;
    }

    void handle(JsonObject conf) {
      Handler<JsonObject> succ;
      boolean isPaused;
      synchronized (this) {
        succ = handler;
        isPaused = paused;
        if (paused) {
          last = conf;
        }
      }

      if (!isPaused && succ != null) {
        succ.handle(conf);
      }

    }

    void fail(Throwable cause) {
      Handler<Throwable> err;
      synchronized (this) {
        err = exceptionHandler;
      }

      if (err != null) {
        err.handle(cause);
      }

    }

    void close() {
      Handler<Void> handler;
      synchronized (this) {
        handler = endHandler;
      }
      if (handler != null) {
        handler.handle(null);
      }
    }
  }
}
