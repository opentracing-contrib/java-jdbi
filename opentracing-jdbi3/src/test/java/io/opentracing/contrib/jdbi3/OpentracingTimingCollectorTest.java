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
import static org.junit.Assert.assertTrue;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.core.statement.TimingCollector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("deprecation") // The class under test is deprecated.
public class OpentracingTimingCollectorTest {
  private Jdbi dbi;
  private Handle handle;

  @Before
  public void before() {
    dbi = Jdbi.create("jdbc:h2:mem:dbi", "sa", "");
    handle = dbi.open();
    handle.execute("CREATE TABLE accounts (id BIGINT AUTO_INCREMENT, PRIMARY KEY (id))");
  }

  @After
  public void after() {
    handle.execute("DROP TABLE accounts");
    handle.close();
  }

  @Test
  public void testParentage() {
    MockTracer tracer = new MockTracer();
    dbi.setTimingCollector(new OpentracingTimingCollector(tracer));

    // The actual JDBI code:
    try (Handle handle = dbi.open()) {
      Tracer.SpanBuilder parentBuilder = tracer.buildSpan("parent span");
      Span parent = parentBuilder.start();
      Query statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
      OpentracingTimingCollector.setParent(statement, parent);

      // A Span will be created automatically and will reference `parent`.
      List<Map<String, Object>> results = statement.mapToMap().list();
      parent.finish();
    }

    List<MockSpan> finishedSpans = tracer.finishedSpans();
    assertEquals(finishedSpans.size(), 2);
    MockSpan parentSpan = finishedSpans.get(1);
    MockSpan childSpan = finishedSpans.get(0);
    assertEquals("parent span", parentSpan.operationName());
    assertEquals("Jdbi Statement", childSpan.operationName());
    assertEquals(parentSpan.context().traceId(), childSpan.context().traceId());
    assertEquals(parentSpan.context().spanId(), childSpan.parentId());
  }

  @Test
  public void testCallNextTracer() {
    MockTracer tracer = new MockTracer();

    class TestTimingCollector implements TimingCollector {
      boolean called = false;

      @Override
      public void collect(long elapsedNs, StatementContext ctx) {
        called = true;
      }
    }

    TestTimingCollector subject = new TestTimingCollector();

    dbi.setTimingCollector(new OpentracingTimingCollector(tracer, subject));

    // The actual JDBI code:
    try (Handle handle = dbi.open()) {
      Tracer.SpanBuilder parentBuilder = tracer.buildSpan("parent span");
      Span parent = parentBuilder.start();
      Query statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
      OpentracingTimingCollector.setParent(statement, parent);

      // A Span will be created automatically and will reference `parent`.
      List<Map<String, Object>> results = statement.mapToMap().list();
      parent.finish();
    }

    assertTrue(subject.called);
  }

  @Test
  public void testDecorations() {
    MockTracer tracer = new MockTracer();
    dbi.setTimingCollector(new OpentracingTimingCollector(tracer, new SpanDecorator() {
      @Override
      public String generateOperationName(StatementContext ctx) {
        return "custom name";
      }

      @Override
      public void decorateSpan(Span jdbiSpan, StatementContext ctx) {
        jdbiSpan.setTag("testTag", "testVal");
      }
    }));

    // The actual JDBI code:
    try (Handle handle = dbi.open()) {
      Query statement = handle.createQuery("SELECT COUNT(*) FROM " +
          "accounts");
      List<Map<String, Object>> results = statement.mapToMap().list();
    }

    List<MockSpan> finishedSpans = tracer.finishedSpans();
    assertEquals(finishedSpans.size(), 1);
    MockSpan span = finishedSpans.get(0);
    assertEquals("custom name", span.operationName());
    assertEquals("testVal", span.tags().get("testTag"));
  }

  @Test
  public void testActiveSpanSource() {
    MockTracer tracer = new MockTracer();

    Tracer.SpanBuilder parentBuilder = tracer.buildSpan("active span");
    final Span activeSpan = parentBuilder.start();
    dbi.setTimingCollector(new OpentracingTimingCollector(tracer, ctx -> activeSpan));

    // The actual JDBI code:
    try (Handle handle = dbi.open()) {
      Query statement = handle.createQuery("SELECT COUNT(*) FROM " +
          "accounts");
      List<Map<String, Object>> results = statement.mapToMap().list();
    }
    activeSpan.finish();

    // The finished spans should include the parent linkage via the ActiveSpanSource.
    List<MockSpan> finishedSpans = tracer.finishedSpans();
    assertEquals(finishedSpans.size(), 2);
    MockSpan parentSpan = finishedSpans.get(1);
    assertEquals("active span", parentSpan.operationName());

    MockSpan childSpan = finishedSpans.get(0);
    assertEquals("Jdbi Statement", childSpan.operationName());
    assertEquals("java-jdbi", childSpan.tags().get(Tags.COMPONENT.getKey()));
    assertEquals("SELECT COUNT(*) FROM accounts", childSpan.tags().get(Tags.DB_STATEMENT.getKey()));

    assertEquals(parentSpan.context().traceId(), childSpan.context().traceId());
    assertEquals(parentSpan.context().spanId(), childSpan.parentId());
  }

  @Test
  public void testChainsToNext() {
    MockTracer tracer = new MockTracer();
    Tracer.SpanBuilder parentBuilder = tracer.buildSpan("active span");

    class TestTimingCollector implements TimingCollector {
      boolean called = false;

      @Override
      public void collect(long elapsedNs, StatementContext ctx) {
        called = true;
      }
    }

    TestTimingCollector subject = new TestTimingCollector();

    final Span activeSpan = parentBuilder.start();
    dbi.setTimingCollector(new OpentracingTimingCollector(tracer, SpanDecorator.DEFAULT,
        ctx -> activeSpan,
        subject)
    );

    // The actual Jdbi code:
    {
      Handle handle = dbi.open();
      Query statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
      List<Map<String, Object>> results = statement.mapToMap().list();
    }

    activeSpan.finish();

    assertTrue(subject.called);
  }
}
