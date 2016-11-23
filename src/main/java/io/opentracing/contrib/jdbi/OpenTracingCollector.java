package io.opentracing.contrib.jdbi;

import io.opentracing.Span;
import io.opentracing.Tracer;
import org.skife.jdbi.v2.SQLStatement;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.TimingCollector;

/**
 * OpenTracingCollector is a JDBI TimingCollector that creates OpenTracing Spans for each JDBI SQLStatement.
 *
 * <p>Example usage:
 * <pre>{@code
 * io.opentracing.Tracer tracer = ...;
 * DBI dbi = ...;
 *
 * // One time only: bind OpenTracing to the DBI instance as a TimingCollector.
 * dbi.setTimingCollector(new OpenTracingCollector(tracer));
 *
 * // Elsewhere, anywhere a `Handle` is available:
 * Handle handle = ...;
 * Span parentSpan = ...;  // optional
 *
 * // Create statements as usual with your `handle` instance.
 *  Query<Map<String, Object>> statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
 *
 * // If a parent Span is available, establish the relationship via setParent.
 * OpenTracingCollector.setParent(statement, parent);
 *
 * // Use JDBI as per usual, and Spans will be created for every SQLStatement automatically.
 * List<Map<String, Object>> results = statement.list();
 * }</pre>
 */
@SuppressWarnings("WeakerAccess")
public class OpenTracingCollector implements TimingCollector {
    public final static String PARENT_SPAN_ATTRIBUTE_KEY = "io.opentracing.parent";

    private final Tracer tracer;
    private final SpanDecorator spanDecorator;
    private final ActiveSpanSource activeSpanSource;
    private final TimingCollector next;

    public OpenTracingCollector(Tracer tracer) {
        this(tracer, SpanDecorator.DEFAULT);
    }

    /**
     * @param tracer the OpenTracing tracer to trace JDBI calls.
     * @param next a timing collector to "chain" to. When collect is called on
     *             this TimingCollector, collect will also be called on 'next'
     */
    @SuppressWarnings("unused")
    public OpenTracingCollector(Tracer tracer, TimingCollector next) {
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
     * @param tracer the OpenTracing tracer to trace JDBI calls.
     * @param spanDecorator the SpanDecorator used to name and decorate spans.
     *                      @see SpanDecorator
     * @param activeSpanSource a source that can provide the currently active
     *                         span when creating a child span.
     *                         @see ActiveSpanSource
     * @param next a timing collector to "chain" to. When collect is called on
     *             this TimingCollector, collect will also be called on 'next'
     */
    public OpenTracingCollector(Tracer tracer, SpanDecorator spanDecorator, ActiveSpanSource activeSpanSource, TimingCollector next) {
        this.tracer = tracer;
        this.spanDecorator = spanDecorator;
        this.activeSpanSource = activeSpanSource;
        this.next = next;
    }

    public void collect(long elapsedNanos, StatementContext statementContext) {
        long nowMicros = System.currentTimeMillis() * 1000;
        Tracer.SpanBuilder builder = tracer
                .buildSpan(spanDecorator.generateOperationName(statementContext))
                .withStartTimestamp(nowMicros - (elapsedNanos / 1000));
        Span parent = (Span)statementContext.getAttribute(PARENT_SPAN_ATTRIBUTE_KEY);
        if (parent == null && this.activeSpanSource != null) {
            parent = this.activeSpanSource.activeSpan(statementContext);
        }
        if (parent != null) {
            builder = builder.asChildOf(parent);
        }
        Span collectSpan = builder.start();
        spanDecorator.decorateSpan(collectSpan, elapsedNanos, statementContext);
        try {
            collectSpan.log("SQL query finished", statementContext.getRawSql());
        } finally {
            collectSpan.finish(nowMicros);
        }

        if (next != null) {
            next.collect(elapsedNanos, statementContext);
        }
    }

    /**
     * Establish an explicit parent relationship for the (child) Span associated with a SQLStatement.
     *
     * @param statement the JDBI SQLStatement which will act as the child of `parent`
     * @param parent the parent Span for `statement`
     */
    public static void setParent(SQLStatement<?> statement, Span parent) {
        statement.getContext().setAttribute(PARENT_SPAN_ATTRIBUTE_KEY, parent);
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
                return "DBI Statement";
            }

            @Override
            public void decorateSpan(Span jdbiSpan, long elapsedNanos, StatementContext ctx) {
                // (by default, do nothing)
            }
        };

        /**
         * Transform an DBI StatementContext into an OpenTracing Span operation name.
         *
         * @param ctx the StatementContext passed to TimingCollector.collect()
         * @return an operation name suitable for the associated OpenTracing Span
         */
        String generateOperationName(StatementContext ctx);

        /**
         * Decorate the given span with additional tags or logs. Implementations may or may not need to refer
         * to the StatementContext.
         *
         * @param jdbiSpan the JDBI Span to decorate (before `finish` is called)
         * @param elapsedNanos the elapsedNanos passed to TimingCollector.collect()
         * @param ctx the StatementContext passed to TimingCollector.collect()
         */
        void decorateSpan(Span jdbiSpan, long elapsedNanos, StatementContext ctx);
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
         * Get the active Span (to use as a parent for any DBI Spans). Implementations may or may not need to refer
         * to the StatementContext.
         *
         * @param ctx the StatementContext that needs to be collected and traced
         * @return the currently active Span (for this thread, etc), or null if no such Span could be found.
         */
        Span activeSpan(StatementContext ctx);
    }
}
