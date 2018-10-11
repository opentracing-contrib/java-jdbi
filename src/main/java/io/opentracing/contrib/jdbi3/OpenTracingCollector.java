package io.opentracing.contrib.jdbi3;

import io.opentracing.Span;
import io.opentracing.Tracer;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.SqlStatement;
import org.jdbi.v3.core.statement.StatementContext;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;

/**
 * OpenTracingCollector is a Jdbi SqlLogger that creates OpenTracing Spans for each Jdbi SQLStatement.
 *
 * <p>Example usage:
 * <pre>{@code
 * io.opentracing.Tracer tracer = ...;
 * Jdbi dbi = ...;
 *
 * // One time only: bind OpenTracing to the Jdbi instance as a SqlLogger.
 * dbi.setSqlLogger(new OpenTracingCollector(tracer));
 *
 * // Elsewhere, anywhere a `Handle` is available:
 * Handle handle = ...;
 * Span parentSpan = ...;  // optional
 *
 * // Create statements as usual with your `handle` instance.
 *  Query statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
 *
 * // If a parent Span is available, establish the relationship via setParent.
 * OpenTracingCollector.setParent(statement, parent);
 *
 * // Use Jdbi as per usual, and Spans will be created for every SQLStatement automatically.
 * List<Map<String, Object>> results = statement.mapToMap().list();
 * }</pre>
 */
@SuppressWarnings("WeakerAccess,unused")
public class OpenTracingCollector implements SqlLogger {
    public final static String PARENT_SPAN_ATTRIBUTE_KEY = "io.opentracing.parent";

    private final Tracer tracer;
    private final SpanDecorator spanDecorator;
    private final ActiveSpanSource activeSpanSource;
    private final SqlLogger next;

    public OpenTracingCollector(Tracer tracer) {
        this(tracer, SpanDecorator.DEFAULT);
    }

    /**
     * @param tracer the OpenTracing tracer to trace Jdbi calls.
     * @param next a timing collector to "chain" to. When collect is called on
     *             this SqlLogger, collect will also be called on 'next'
     */
    @SuppressWarnings("unused")
    public OpenTracingCollector(Tracer tracer, SqlLogger next) {
        this(tracer, SpanDecorator.DEFAULT, null, null);
    }

    public OpenTracingCollector(Tracer tracer, SpanDecorator spanDecorator) {
        this(tracer, spanDecorator, null);
    }

    public OpenTracingCollector(Tracer tracer, ActiveSpanSource spanSource) {
        this(tracer, SpanDecorator.DEFAULT, spanSource);
    }

    public OpenTracingCollector(Tracer tracer, SpanDecorator spanDecorator, ActiveSpanSource activeSpanSource) {
        this(tracer, spanDecorator, activeSpanSource, null);
    }

    /**
     * @param tracer the OpenTracing tracer to trace Jdbi calls.
     * @param spanDecorator the SpanDecorator used to name and decorate spans.
     *                      @see SpanDecorator
     * @param activeSpanSource a source that can provide the currently active
     *                         span when creating a child span.
     *                         @see ActiveSpanSource
     * @param next a timing collector to "chain" to. When logAfterExecution is called on
     *             this SqlLogger, logAfterExecution will also be called on 'next'
     */
    public OpenTracingCollector(Tracer tracer, SpanDecorator spanDecorator, ActiveSpanSource activeSpanSource, SqlLogger next) {
        this.tracer = tracer;
        this.spanDecorator = spanDecorator;
        this.activeSpanSource = activeSpanSource;
        this.next = next;
    }

    @Override
    public void logAfterExecution(StatementContext statementContext) {
        long nowMicros = System.currentTimeMillis() * 1000;
        Tracer.SpanBuilder builder = tracer
            .buildSpan(spanDecorator.generateOperationName(statementContext))
            .withStartTimestamp(nowMicros - (statementContext.getElapsedTime(ChronoUnit.MICROS)));
        Span parent = (Span)statementContext.getAttribute(PARENT_SPAN_ATTRIBUTE_KEY);
        if (parent == null && this.activeSpanSource != null) {
            parent = this.activeSpanSource.activeSpan(statementContext);
        }
        if (parent != null) {
            builder = builder.asChildOf(parent);
        }
        Span collectSpan = builder.start();
        spanDecorator.decorateSpan(collectSpan, statementContext);
        try {

            HashMap<String, String> values = new HashMap<>();
            values.put("event", "SQL query finished");
            values.put("db.statement", statementContext.getRawSql());
            collectSpan.log(nowMicros, values);
        } finally {
            collectSpan.finish(nowMicros);
        }

        if (next != null) {
            next.logAfterExecution(statementContext);
        }
    }



    /**
     * Establish an explicit parent relationship for the (child) Span associated with a SQLStatement.
     *
     * @param statement the Jdbi SqlStatement which will act as the child of `parent`
     * @param parent the parent Span for `statement`
     */
    public static void setParent(SqlStatement<?> statement, Span parent) {
        statement.getContext().define(PARENT_SPAN_ATTRIBUTE_KEY, parent);
    }

    /**
     * SpanDecorator allows the OpenTracingCollector user to control the precise naming and decoration of OpenTracing
     * Spans emitted by the collector.
     *
     * @see OpenTracingCollector#OpenTracingCollector(Tracer, SpanDecorator)
     */
    public interface SpanDecorator {
        SpanDecorator DEFAULT = new SpanDecorator() {
            public String generateOperationName(StatementContext ctx) {
                return "Jdbi Statement";
            }

            @Override
            public void decorateSpan(Span jdbiSpan, StatementContext ctx) {
                // (by default, do nothing)
            }
        };

        /**
         * Transform an Jdbi StatementContext into an OpenTracing Span operation name.
         *
         * @param ctx the StatementContext passed to SqlLogger.logAfterExecution()
         * @return an operation name suitable for the associated OpenTracing Span
         */
        String generateOperationName(StatementContext ctx);

        /**
         * Decorate the given span with additional tags or logs. Implementations may or may not need to refer
         * to the StatementContext.
         * @param jdbiSpan the Jdbi Span to decorate (before `finish` is called)
         * @param ctx the StatementContext passed to SqlLogger.logAfterExecution()
         */
        void decorateSpan(Span jdbiSpan, StatementContext ctx);
    }

    /**
     * An abstract API that allows the OpenTracingCollector to customize how parent Spans are discovered.
     *
     * For instance, if Spans are stored in a thread-local variable, an ActiveSpanSource could access them like so:
     * <p>Example usage:
     * <pre>{@code
     * public class SomeClass {
     *     // Thread local variable containing each thread's ID
     *     private static final ThreadLocal<Span> activeSpan =
     *         new ThreadLocal<Span>() {
     *             protected Integer initialValue() {
     *                 return null;
     *             }
     *         };
     * };
     *
     * ... elsewhere ...
     * ActiveSpanSource spanSource = new ActiveSpanSource() {
     *     public Span activeSpan(StatementContext ctx) {
     *         // (In this example we ignore `ctx` entirely)
     *         return activeSpan.get();
     *     }
     * };
     * OpenTracingCollector otColl = new OpenTracingCollector(tracer, spanSource);
     * ...
     * }</pre>
     */
    public interface ActiveSpanSource {
        /**
         * Get the active Span (to use as a parent for any Jdbi Spans). Implementations may or may not need to refer
         * to the StatementContext.
         *
         * @param ctx the StatementContext that needs to be collected and traced
         * @return the currently active Span (for this thread, etc), or null if no such Span could be found.
         */
        Span activeSpan(StatementContext ctx);
    }
}
