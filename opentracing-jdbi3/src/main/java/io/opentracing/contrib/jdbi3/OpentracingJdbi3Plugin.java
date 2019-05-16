/*
 * Copyright 2016-2018 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.opentracing.contrib.jdbi3;

import io.opentracing.Tracer;
import java.util.logging.Logger;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.spi.JdbiPlugin;
import org.jdbi.v3.core.statement.SqlStatements;

/**
 * Jdbi 3 {@linkplain JdbiPlugin plugin} that configures either the {@linkplain
 * OpentracingSqlLogger} or the {@linkplain OpentracingTimingCollector} (in case of Jdbi &lt; 3.2).
 * <p>
 * <strong>Usage:</strong><br>
 * You can provide your own {@linkplain Tracer} and install the plugin manually:
 * <pre><code>
 * Jdbi jdbi = ...
 * Tracer tracer = ...
 * jdbi.installPlugin(new OpentracingJdbi3Plugin(tracer));
 * </code></pre>
 * <p>
 * Alternatively, if you have a {@code GlobalTracer} from the
 * <a href="https://github.com/opentracing/opentracing-java/tree/master/opentracing-util">OpenTracing
 * util library</a> you can take advantage of the automatic Plugin resolution in Jdbi:
 * <pre><code>
 * GlobalTracer.registerIfAbsent(() -> initializeMyTracer());
 * Jdbi jdbi = ...
 * jdbi.installPlugins();
 * </code></pre>
 *
 * @author Sjoerd Talsma
 */
public class OpentracingJdbi3Plugin implements JdbiPlugin {
  private static final Logger LOGGER = Logger.getLogger(OpentracingJdbi3Plugin.class.getName());

  private final Tracer tracer;

  /**
   * Constructor for the plugin that will use the {@code GlobalTracer}
   */
  public OpentracingJdbi3Plugin() {
    this(null);
  }

  /**
   * Constructor for the plugin that will use a specified {@linkplain Tracer}.
   *
   * @param tracer The tracer to use (optional, provide {@code null} to fallback to the {@code
   * GlobalTracer})
   */
  public OpentracingJdbi3Plugin(Tracer tracer) {
    if (tracer == null) {
      try {
        // Use fully-qualified name: take care not to import anything from optional io.opentracing.util package!
        tracer = io.opentracing.util.GlobalTracer.get();
      } catch (LinkageError globalTracerUnavailable) {
        LOGGER.warning(() -> "No tracer specified and Globaltracer cannot be used. " +
            "Please provide a tracer or add opentracing-util to the classpath.");
      }
    }
    this.tracer = tracer;
  }

  @Override
  @SuppressWarnings("deprecation") // Fallback behaviour is deprecated to discourage use
  public void customizeJdbi(Jdbi jdbi) {
    if (tracer != null) {
      final SqlStatements config = jdbi.getConfig(SqlStatements.class);
      if (config.getSqlLogger() instanceof OpentracingSqlLogger) {
        return;
      }
      try {
        config.setSqlLogger(new OpentracingSqlLogger(tracer, config.getSqlLogger()));
      } catch (LinkageError sqlLoggerApiUnavailable) {
        if (config.getTimingCollector() instanceof OpentracingTimingCollector) {
          return;
        }
        LOGGER.warning(() -> "Could not configure Opentracing SqlLogger implementation. " +
            "Falling back to TimingCollector. Please consider using JDBI version 3.2 or greater.");
        config.setTimingCollector(
            new OpentracingTimingCollector(tracer, config.getTimingCollector()));
      }
    }
  }

  /**
   * Provides a human-readable string of this plugin with the tracer used (e.g. when logged by
   * Jdbi).
   *
   * @return The name of the plugin and the tracer used.
   */
  @Override
  public String toString() {
    return getClass().getSimpleName() + "{tracer=" + tracer + '}';
  }
}
