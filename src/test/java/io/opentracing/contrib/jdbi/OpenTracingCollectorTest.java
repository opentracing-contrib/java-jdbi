package io.opentracing.contrib.jdbi;

import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.Query;
import org.skife.jdbi.v2.StatementContext;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

// REQUIRES: a mysql database running on localhost.
public class OpenTracingCollectorTest {
    @BeforeClass
    public static void setUp() throws Exception {
        {
            DBI dbi = new DBI("jdbc:mysql://localhost/", "root", "");
            Handle handle = dbi.open();
            handle.execute("CREATE DATABASE IF NOT EXISTS _jdbi_test_db");
        }
        {
            DBI dbi = new DBI("jdbc:mysql://localhost/_jdbi_test_db", "root", "");
            Handle handle = dbi.open();
            handle.execute("CREATE TABLE IF NOT EXISTS accounts (id BIGINT AUTO_INCREMENT, PRIMARY KEY (id))");
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        DBI dbi = new DBI("jdbc:mysql://localhost/", "root", "");
        Handle handle = dbi.open();
        handle.execute("DROP DATABASE _jdbi_test_db");
    }

    @Test
    public void testParentage() {
        MockTracer tracer = new MockTracer();
        DBI dbi = new DBI("jdbc:mysql://localhost/_jdbi_test_db", "root", "");
        dbi.setTimingCollector(new OpenTracingCollector(tracer));

        // The actual JDBI code:
        {
            Handle handle = dbi.open();
            Tracer.SpanBuilder parentBuilder = tracer.buildSpan("parent span");
            try (Span parent = parentBuilder.start()) {
                Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
                OpenTracingCollector.setParent(statement, parent);

                // A Span will be created automatically and will reference `parent`.
                List<Map<String, Object>> results = statement.list();
            }
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
    public void testCustomOperationName() {
        MockTracer tracer = new MockTracer();
        DBI dbi = new DBI("jdbc:mysql://localhost/_jdbi_test_db", "root", "");
        dbi.setTimingCollector(new OpenTracingCollector(tracer, new OpenTracingCollector.OperationNamer() {
            @Override
            public String generateOperationName(StatementContext ctx) {
                return "custom name";
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
    }

    @Test
    // Requires a mysql database running on localhost.
    public void testActiveSpanSource() {
        MockTracer tracer = new MockTracer();
        DBI dbi = new DBI("jdbc:mysql://localhost/_jdbi_test_db", "root", "");

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
}
