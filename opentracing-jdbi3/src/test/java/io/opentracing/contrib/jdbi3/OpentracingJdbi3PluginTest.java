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

import static org.junit.Assert.assertEquals;

import io.opentracing.Scope;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.GlobalTracer;
import io.opentracing.util.GlobalTracerTestUtil;
import java.util.List;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the automatic plugin configuration.
 *
 * @author Sjoerd Talsma
 */
public class OpentracingJdbi3PluginTest {

  @Before
  @After
  public void resetGlobalTracer() {
    GlobalTracerTestUtil.resetGlobalTracer();
  }

  @Test
  public void testAutomaticPluginDiscoveryUsingGlobalTracer() {
    MockTracer tracer = new MockTracer();
    GlobalTracer.register(tracer);

    Jdbi jdbi = Jdbi.create("jdbc:h2:mem:dbi", "sa", "").installPlugins();

    MockSpan parent = tracer.buildSpan("parent span").start();
    long traceId = parent.context().traceId();
    long parentId = parent.context().spanId();
    try (Scope scope = tracer.scopeManager().activate(parent, false);
        Handle handle = jdbi.open();
        Query query = handle.createQuery("SELECT COUNT(*) FROM accounts")
    ) {
      handle.execute("CREATE TABLE accounts (id BIGINT AUTO_INCREMENT, PRIMARY KEY (id))");
      assertEquals("Row count", 0L,
          (long) query.reduceResultSet(0L, (prev, rs, ctx) -> prev + rs.getLong(1)));
      handle.execute("DROP TABLE accounts");
    } finally {
      parent.finish();
    }

    List<MockSpan> finishedSpans = tracer.finishedSpans();
    assertEquals("Finished spans", 4, finishedSpans.size());

    assertEquals("1st operation name", finishedSpans.get(0).operationName(), "Jdbi Statement");
    assertEquals("1st traceId", traceId, finishedSpans.get(0).context().traceId());
    assertEquals("1st parentId", parentId, finishedSpans.get(0).parentId());
    assertEquals("2nd operation name", finishedSpans.get(1).operationName(), "Jdbi Statement");
    assertEquals("2nd traceId", traceId, finishedSpans.get(1).context().traceId());
    assertEquals("2nd parentId", parentId, finishedSpans.get(1).parentId());
    assertEquals("3rd operation name", finishedSpans.get(2).operationName(), "Jdbi Statement");
    assertEquals("3rd traceId", traceId, finishedSpans.get(2).context().traceId());
    assertEquals("3rd parentId", parentId, finishedSpans.get(2).parentId());
    assertEquals("4th operation name", finishedSpans.get(3).operationName(), "parent span");
    assertEquals("4th traceId", traceId, finishedSpans.get(3).context().traceId());
    assertEquals("4th parentId", 0L, finishedSpans.get(3).parentId());
  }

  @Test
  public void testManualPluginInstallation() {
    MockTracer tracer = new MockTracer();
    OpentracingJdbi3Plugin plugin = new OpentracingJdbi3Plugin(tracer);
    Jdbi jdbi = Jdbi.create("jdbc:h2:mem:dbi", "sa", "").installPlugin(plugin);

    MockSpan parent = tracer.buildSpan("parent span").start();
    long traceId = parent.context().traceId();
    long parentId = parent.context().spanId();
    try (Scope scope = tracer.scopeManager().activate(parent, false); Handle handle = jdbi.open()) {
      handle.execute("CREATE TABLE accounts (id BIGINT AUTO_INCREMENT, PRIMARY KEY (id))");
      try (Query query = handle.createQuery("SELECT COUNT(*) FROM accounts")) {
        assertEquals("Row count", 0L,
            (long) query.reduceResultSet(0L, (prev, rs, ctx) -> prev + rs.getLong(1)));
      }
      handle.execute("DROP TABLE accounts");
    } finally {
      parent.finish();
    }

    List<MockSpan> finishedSpans = tracer.finishedSpans();
    assertEquals("Finished spans", 4, finishedSpans.size());

    assertEquals("1st operation name", finishedSpans.get(0).operationName(), "Jdbi Statement");
    assertEquals("1st traceId", traceId, finishedSpans.get(0).context().traceId());
    assertEquals("1st parentId", parentId, finishedSpans.get(0).parentId());
    assertEquals("2nd operation name", finishedSpans.get(1).operationName(), "Jdbi Statement");
    assertEquals("2nd traceId", traceId, finishedSpans.get(1).context().traceId());
    assertEquals("2nd parentId", parentId, finishedSpans.get(1).parentId());
    assertEquals("3rd operation name", finishedSpans.get(2).operationName(), "Jdbi Statement");
    assertEquals("3rd traceId", traceId, finishedSpans.get(2).context().traceId());
    assertEquals("3rd parentId", parentId, finishedSpans.get(2).parentId());
    assertEquals("4th operation name", finishedSpans.get(3).operationName(), "parent span");
    assertEquals("4th traceId", traceId, finishedSpans.get(3).context().traceId());
    assertEquals("4th parentId", 0L, finishedSpans.get(3).parentId());
  }

  @Test
  public void testPluginToString() {
    MockTracer tracer = new MockTracer();
    assertEquals("OpentracingJdbi3Plugin{tracer=GlobalTracer{NoopTracer}}",
        new OpentracingJdbi3Plugin().toString());
    assertEquals("OpentracingJdbi3Plugin{tracer=" + tracer + "}",
        new OpentracingJdbi3Plugin(tracer).toString());
  }
}
