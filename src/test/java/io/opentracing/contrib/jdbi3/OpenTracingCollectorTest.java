package io.opentracing.contrib.jdbi3;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// REQUIRES: a mysql database running on localhost.
public class OpenTracingCollectorTest {
    private static Jdbi getLocalJdbi() {
        return getLocalJdbi("");
    }
    private static Jdbi getLocalJdbi(String dbName) {
        return Jdbi.create("jdbc:mysql://127.0.0.1/" + dbName, "root", "");
    }

    @BeforeClass
    public static void setUp() throws Exception {
        Class.forName("com.mysql.jdbc.Driver");
        {
            Jdbi dbi = getLocalJdbi();
            Handle handle = dbi.open();
            handle.execute("CREATE DATABASE IF NOT EXISTS _jdbi_test_db");
        }
        {
            Jdbi dbi = getLocalJdbi("_jdbi_test_db");
            Handle handle = dbi.open();
            handle.execute("CREATE TABLE IF NOT EXISTS accounts (id BIGINT AUTO_INCREMENT, PRIMARY KEY (id))");
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        Jdbi dbi = getLocalJdbi();
        Handle handle = dbi.open();
        handle.execute("DROP DATABASE _jdbi_test_db");
    }

    @Test
    public void testParentage() {
        MockTracer tracer = new MockTracer();
        Jdbi dbi = getLocalJdbi("_jdbi_test_db");
        dbi.setSqlLogger(new OpenTracingCollector(tracer));

        // The actual Jdbi code:
        {
            Handle handle = dbi.open();
            Tracer.SpanBuilder parentBuilder = tracer.buildSpan("parent span");
            Span parent = parentBuilder.start();
            Query statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
            OpenTracingCollector.setParent(statement, parent);

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
    // Requires a mysql database running on localhost.
    public void testDecorations() {
        MockTracer tracer = new MockTracer();
        Jdbi dbi = getLocalJdbi("_jdbi_test_db");
        dbi.setSqlLogger(new OpenTracingCollector(tracer, new OpenTracingCollector.SpanDecorator(){
            @Override
            public String generateOperationName(StatementContext ctx) {
                return "custom name";
            }

            @Override
            public void decorateSpan(Span jdbiSpan, StatementContext ctx) {
                jdbiSpan.setTag("testTag", "testVal");
            }
        } ));

        // The actual Jdbi code:
        {
            Handle handle = dbi.open();
            Query statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
            List<Map<String, Object>> results = statement.mapToMap().list();
        }

        List<MockSpan> finishedSpans = tracer.finishedSpans();
        assertEquals(finishedSpans.size(), 1);
        MockSpan span = finishedSpans.get(0);
        assertEquals("custom name", span.operationName());
        assertEquals("testVal", span.tags().get("testTag"));
    }

    @Test
    // Requires a mysql database running on localhost.
    public void testActiveSpanSource() {
        MockTracer tracer = new MockTracer();
        Jdbi dbi = getLocalJdbi("_jdbi_test_db");

        Tracer.SpanBuilder parentBuilder = tracer.buildSpan("active span");
        final Span activeSpan = parentBuilder.start();
        dbi.setSqlLogger(new OpenTracingCollector(tracer, ctx -> activeSpan));

        // The actual Jdbi code:
        {
            Handle handle = dbi.open();
            Query statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
            List<Map<String, Object>> results = statement.mapToMap().list();
        }
        activeSpan.finish();

        // The finished spans should include the parent linkage via the ActiveSpanSource.
        List<MockSpan> finishedSpans = tracer.finishedSpans();
        assertEquals(finishedSpans.size(), 2);
        MockSpan parentSpan = finishedSpans.get(1);
        MockSpan childSpan = finishedSpans.get(0);
        assertEquals("active span", parentSpan.operationName());
        assertEquals("Jdbi Statement", childSpan.operationName());
        assertEquals(parentSpan.context().traceId(), childSpan.context().traceId());
        assertEquals(parentSpan.context().spanId(), childSpan.parentId());
    }

    @Test
    public void testChainsToNext() {
        MockTracer tracer = new MockTracer();
        Jdbi dbi = getLocalJdbi("_jdbi_test_db");
        Tracer.SpanBuilder parentBuilder = tracer.buildSpan("active span");

        class TestTimingCollector implements SqlLogger {
            boolean called = false;

            @Override
            public void logAfterExecution(StatementContext context) {
                called = true;
            }
        }

        TestTimingCollector subject = new TestTimingCollector();

        final Span activeSpan = parentBuilder.start();
        dbi.setSqlLogger(new OpenTracingCollector(tracer, OpenTracingCollector.SpanDecorator.DEFAULT,
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
