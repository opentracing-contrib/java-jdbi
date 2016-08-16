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
public class OpenTracingCollector implements TimingCollector {
    public final static String PARENT_SPAN_ATTRIBUTE_KEY = "io.opentracing.parent";

    private final Tracer tracer;
    private final OperationNamer operationNamer;
    private final ActiveSpanSource activeSpanSource;

    public OpenTracingCollector(Tracer tracer) {
        this(tracer, OperationNamer.DEFAULT);
    }

    public OpenTracingCollector(Tracer tracer, OperationNamer operationNamer) {
        this(tracer, operationNamer, null);
    }
    public OpenTracingCollector(Tracer tracer, ActiveSpanSource spanSource) {
        this(tracer, OperationNamer.DEFAULT, spanSource);
    }

    public OpenTracingCollector(Tracer tracer, OperationNamer operationNamer, ActiveSpanSource spanSource) {
        this.tracer = tracer;
        this.operationNamer = operationNamer;
        this.activeSpanSource = spanSource;
    }

    @Override
    public void collect(long elapsedNanos, StatementContext statementContext) {
        long nowMicros = System.currentTimeMillis() * 1000;
        Tracer.SpanBuilder builder = tracer
                .buildSpan(operationNamer.generateOperationName(statementContext))
                .withStartTimestamp(nowMicros - (elapsedNanos / 1000));
        Span parent = (Span)statementContext.getAttribute(PARENT_SPAN_ATTRIBUTE_KEY);
        if (parent == null && this.activeSpanSource != null) {
            parent = this.activeSpanSource.activeSpan(statementContext);
        }
        if (parent != null) {
            builder = builder.asChildOf(parent);
        }
        Span collectSpan = builder.start();
        try {
            collectSpan.log("Raw SQL", statementContext.getRawSql());
        } finally {
            collectSpan.finish(nowMicros);
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
     * OperationNamer allows the OpenTracingCollector user to control the precise naming of OpenTracing Spans emitted
     * by the collector.
     *
     * @see OpenTracingCollector#OpenTracingCollector(Tracer, OperationNamer)
     */
    public interface OperationNamer {
        public static OperationNamer DEFAULT = new OperationNamer() {
            @Override
            public String generateOperationName(StatementContext ctx) {
                return "DBI Statement";
            }
        };

        /**
         * Transform an DBI StatementContext into an OpenTracing Span operation name.
         *
         * @return an operation name suitable for an OpenTracing Span
         */
        public String generateOperationName(StatementContext ctx);
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
     *             @Override protected Integer initialValue() {
     *                 return null;
     *             }
     *         };
     *     };
     *
     *     ... elsewhere ...
     *     {
     *         ActiveSpanSource spanSource = new ActiveSpanSource() {
     *             @Override
     *             public Span activeSpan(StatementContext ctx) {
     *                 // (In this example we ignore `ctx` entirely)
     *                 return activeSpan.get();
     *             }
     *         };
     *         OpenTracingCollector otColl = new OpenTracingCollector(tracer, spanSource);
     *         ...
     *     }
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
        public Span activeSpan(StatementContext ctx);
    }
}
