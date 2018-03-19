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
package io.opentracing.contrib.jdbi;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.TimingCollector;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OpenTracingCollectorTest {
    private DBI dbi;
    private Handle handle;

    @Before
    public void before() {
        dbi = new DBI("jdbc:h2:mem:dbi", "sa", "");
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
        dbi.setTimingCollector(new OpenTracingCollector(tracer));

        // The actual JDBI code:
        try (Handle handle = dbi.open()) {
            Tracer.SpanBuilder parentBuilder = tracer.buildSpan("parent span");
            Span parent = parentBuilder.start();
            Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM " +
                    "accounts");
            OpenTracingCollector.setParent(statement, parent);

            // A Span will be created automatically and will reference `parent`.
            List<Map<String, Object>> results = statement.list();
            parent.finish();
        }

        List<MockSpan> finishedSpans = tracer.finishedSpans();
        assertEquals(finishedSpans.size(), 2);
        MockSpan parentSpan = finishedSpans.get(1);
        MockSpan childSpan = finishedSpans.get(0);
        assertEquals("parent span", parentSpan.operationName());
        assertEquals("DBI Statement", childSpan.operationName());
        assertEquals(parentSpan.context().traceId(), childSpan.context().traceId());
        assertEquals(parentSpan.context().spanId(), childSpan.parentId());
    }

    @Test
    public void testCallNextTracer() {
        MockTracer tracer = new MockTracer();

        class TestTimingCollector implements TimingCollector {
            boolean called = false;
            @Override
            public void collect(long l, StatementContext statementContext) {
                called = true;
            }
        };

        TestTimingCollector subject = new TestTimingCollector();

        dbi.setTimingCollector(new OpenTracingCollector(tracer, subject));

        // The actual JDBI code:
        {
            Handle handle = dbi.open();
            Tracer.SpanBuilder parentBuilder = tracer.buildSpan("parent span");
            Span parent = parentBuilder.start();
            Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
            OpenTracingCollector.setParent(statement, parent);

            // A Span will be created automatically and will reference `parent`.
            List<Map<String, Object>> results = statement.list();
            parent.finish();
        }

        assertTrue(subject.called);
    }

    @Test
    public void testDecorations() {
        MockTracer tracer = new MockTracer();
        dbi.setTimingCollector(new OpenTracingCollector(tracer, new OpenTracingCollector
                .SpanDecorator() {
            @Override
            public String generateOperationName(StatementContext ctx) {
                return "custom name";
            }

            @Override
            public void decorateSpan(Span jdbiSpan, long elapsedNanos, StatementContext ctx) {
                jdbiSpan.setTag("testTag", "testVal");
            }
        }));

        // The actual JDBI code:
        try (Handle handle = dbi.open()) {
            Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM " +
                    "accounts");
            List<Map<String, Object>> results = statement.list();
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
        dbi.setTimingCollector(new OpenTracingCollector(tracer, new OpenTracingCollector
                .ActiveSpanSource() {
            @Override
            public Span activeSpan(StatementContext ctx) {
                return activeSpan;
            }
        }));

        // The actual JDBI code:
        try (Handle handle = dbi.open()) {
            Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM " +
                    "accounts");
            List<Map<String, Object>> results = statement.list();
        }
        activeSpan.finish();

        // The finished spans should include the parent linkage via the ActiveSpanSource.
        List<MockSpan> finishedSpans = tracer.finishedSpans();
        assertEquals(finishedSpans.size(), 2);
        MockSpan parentSpan = finishedSpans.get(1);
        MockSpan childSpan = finishedSpans.get(0);
        assertEquals("active span", parentSpan.operationName());
        assertEquals("DBI Statement", childSpan.operationName());
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
            public void collect(long l, StatementContext statementContext) {
                called = true;
            }
        }

        TestTimingCollector subject = new TestTimingCollector();

        final Span activeSpan = parentBuilder.start();
        dbi.setTimingCollector(new OpenTracingCollector(tracer, OpenTracingCollector
                .SpanDecorator.DEFAULT,
                new OpenTracingCollector.ActiveSpanSource() {
                    @Override
                    public Span activeSpan(StatementContext ctx) {
                        return activeSpan;
                    }
                },
                subject)
        );

        // The actual JDBI code:
        try (Handle handle = dbi.open()) {
            Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM " +
                    "accounts");
            List<Map<String, Object>> results = statement.list();
        }
        activeSpan.finish();

        assertTrue(subject.called);
    }
}
