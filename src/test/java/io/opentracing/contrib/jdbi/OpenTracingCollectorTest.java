package io.opentracing.contrib.jdbi;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.skife.jdbi.v2.*;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

// REQUIRES: a mysql database running on localhost.
public class OpenTracingCollectorTest {
    private static DBI getLocalDBI() {
        return getLocalDBI("");
    }
    private static DBI getLocalDBI(String dbName) {
        return new DBI("jdbc:mysql://127.0.0.1/" + dbName, "root", "");
    }

    @BeforeClass
    public static void setUp() throws Exception {
        Class.forName("com.mysql.jdbc.Driver");
        {
            DBI dbi = getLocalDBI();
            Handle handle = dbi.open();
            handle.execute("CREATE DATABASE IF NOT EXISTS _jdbi_test_db");
        }
        {
            DBI dbi = getLocalDBI("_jdbi_test_db");
            Handle handle = dbi.open();
            handle.execute("CREATE TABLE IF NOT EXISTS accounts (id BIGINT AUTO_INCREMENT, PRIMARY KEY (id))");
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        DBI dbi = getLocalDBI();
        Handle handle = dbi.open();
        handle.execute("DROP DATABASE _jdbi_test_db");
    }

    @Test
    public void testParentage() {
        MockTracer tracer = new MockTracer();
        DBI dbi = getLocalDBI("_jdbi_test_db");
        dbi.setTimingCollector(new OpenTracingCollector(tracer));

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
    // Requires a mysql database running on localhost.
    public void testDecorations() {
        MockTracer tracer = new MockTracer();
        DBI dbi = getLocalDBI("_jdbi_test_db");
        dbi.setTimingCollector(new OpenTracingCollector(tracer, new OpenTracingCollector.SpanDecorator() {
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
        {
            Handle handle = dbi.open();
            Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
            List<Map<String, Object>> results = statement.list();
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
        DBI dbi = getLocalDBI("_jdbi_test_db");

        Tracer.SpanBuilder parentBuilder = tracer.buildSpan("active span");
        final Span activeSpan = parentBuilder.start();
        dbi.setTimingCollector(new OpenTracingCollector(tracer, new OpenTracingCollector.ActiveSpanSource() {
            @Override
            public Span activeSpan(StatementContext ctx) {
                return activeSpan;
            }
        }));

        // The actual JDBI code:
        {
            Handle handle = dbi.open();
            Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
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
        DBI dbi = getLocalDBI("_jdbi_test_db");
        Tracer.SpanBuilder parentBuilder = tracer.buildSpan("active span");

        class TestTimingCollector implements TimingCollector {
            boolean called = false;
            @Override
            public void collect(long l, StatementContext statementContext) {
                called = true;
            }
        };

        TestTimingCollector subject = new TestTimingCollector();

        final Span activeSpan = parentBuilder.start();
        dbi.setTimingCollector(new OpenTracingCollector(tracer, OpenTracingCollector.SpanDecorator.DEFAULT,
                new OpenTracingCollector.ActiveSpanSource() {
                    @Override
                    public Span activeSpan(StatementContext ctx) {
                        return activeSpan;
                    }
                },
                subject)
        );

        // The actual JDBI code:
        {
            Handle handle = dbi.open();
            Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
            List<Map<String, Object>> results = statement.list();
        }

        activeSpan.finish();

        assertTrue(subject.called);
    }
}
