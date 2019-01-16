# OpenTracing JDBI Instrumentation
OpenTracing instrumentation for JDBI.

## Installation

[![Released Version][maven-img]][maven]

pom.xml
```xml
<dependency>
    <groupId>io.opentracing.contrib</groupId>
    <artifactId>opentracing-jdbi3</artifactId>
    <version>VERSION</version>
</dependency>
```

## Usage

### Configuration as a plugin

The `OpentracingJdbi3Plugin` can be automatically detected by Jdbi. 
If not explicitly configured, it will try to use the `GlobalTracer`.
If there is an active `Scope` set in the OpenTracing [ScopeManager], 
the parent span will be set accordingly.

```java
// One time only: Instatiate GlobalTracer, Jdbi and install automatic plugins
Tracer tracer = ...;
GlobalTracer.register(tracer);
Jdbi jdbi = Jdbi.create(...).installPlugins();

// Now all JDBI queries will automatically be traced
// Each span will have the active span from the scope manager as parent span (if present)

// Use Jdbi as per usual, and Spans will be created for every SqlStatement automatically.
try (Handle handle = jdbi.open(); 
     Query query = handle.createQuery("SELECT COUNT(*) FROM accounts")) {
    long count = (long) query.reduceResultSet(0L, (prev, rs, ctx) -> prev + rs.getLong(1)));
}
``` 

### Customising the tracer used by the plugin

If you cannot use the configured `GlobalTracer`,
you can also install the plugin with a specific tracer:
```java
// One time only: Create tracer, Jdbi and install OpentracingJdbi3Plugin
Tracer tracer = ...;
Jdbi jdbi = Jdbi.create(...).installPlugin(new OpentracingJdbi3Plugin(tracer));
```

### Manual OpentracingSqlLogger configuration

If you don't want to use the plugin, the `OpentracingSqlLogger` can still be configured
and used manually:

```java
// Instantiate tracer
Tracer tracer = ...;

// Instatiate Jdbi
Jdbi jdbi = Jdbi.create...;

// One time only: bind OpenTracing to the Jdbi instance as a SqlLogger.  
// OpentracingSqlLogger creates OpenTracing Spans for each Jdbi SqlStatement.
jdbi.setSqlLogger(new OpentracingSqlLogger(tracer));
 
// Elsewhere, anywhere a `Handle` is available:
Handle handle = ...;
Span parentSpan = ...;  // optional
 
// Create statements as usual with your `handle` instance.
Query statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
 
// If a parent Span is available, establish the relationship via setParent.
OpentracingSqlLogger.setParent(statement, parentSpan);
 
// Use Jdbi as per usual, and Spans will be created for every SqlStatement automatically.
List<Map<String, Object>> results = statement.mapToMap().list();
```

### Older Jdbi3 versions

The `OpentracingSqlLogger` obviously implements `SqlLogger`.
This interface was introduced in JDBI version `3.2`.
In case you have an older version of Jdbi3, you should use the now-deprecated
`OpentracingTimingCollector` instead.

This fallback is automatic if you use the `OpentracingJdbi3Plugin`.

Configuration and behaviour of the `OpentracingTimingCollector` is the same
as the `OpentracingSqlLogger` above, except setting the SqlLogger changes from `jdbi.setSqlLogger(..)` to:
```java
jdbi.setTimingCollector(new OpentracingTimingCollector(tracer));
``` 

## License

[Apache 2.0 License](../LICENSE).

  [ci-img]: https://travis-ci.org/opentracing-contrib/java-jdbi.svg?branch=master
  [ci]: https://travis-ci.org/opentracing-contrib/java-jdbi
  [cov-img]: https://coveralls.io/repos/github/opentracing-contrib/java-jdbi/badge.svg?branch=master
  [cov]: https://coveralls.io/github/opentracing-contrib/java-jdbi?branch=master
  [maven-img]: https://img.shields.io/maven-central/v/io.opentracing.contrib/jdbi-opentracing.svg
  [maven]: http://search.maven.org/#search%7Cga%7C1%7Cjdbi-opentracing

  [ScopeManager]: https://github.com/opentracing/opentracing-java/blob/master/opentracing-api/src/main/java/io/opentracing/ScopeManager.java
