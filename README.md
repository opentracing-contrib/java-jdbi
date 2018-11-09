[![Build Status][ci-img]][ci] [![Coverage Status][cov-img]][cov] [![Released Version][maven-img]][maven]

# OpenTracing JDBI Instrumentation
OpenTracing instrumentation for JDBI.

## Installation and Usage

- [Jdbi v2](opentracing-jdbi/README.md)

### Jdbi3
```java
// Instantiate tracer
Tracer tracer = ...;

// Instatiate Jdbi
Jdbi dbi = Jdbi.create...;

// One time only: bind OpenTracing to the Jdbi instance as a SqlLogger.  
// OpenTracingCollector is a Jdbi SqlLogger that creates OpenTracing Spans for each Jdbi SqlStatement.
dbi.setSqlLogger(new OpenTracingCollector(tracer)); //io.opentracing.contrib.**jdbi3**.OpenTracingCollector
 
// Elsewhere, anywhere a `Handle` is available:
Handle handle = ...;
Span parentSpan = ...;  // optional
 
// Create statements as usual with your `handle` instance.
Query statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
 
// If a parent Span is available, establish the relationship via setParent.
OpenTracingCollector.setParent(statement, parent);
 
// Use Jdbi as per usual, and Spans will be created for every SqlStatement automatically.
List<Map<String, Object>> results = statement.mapToMap().list();
```

## License

[Apache 2.0 License](./LICENSE).

[ci-img]: https://travis-ci.org/opentracing-contrib/java-jdbi.svg?branch=master
[ci]: https://travis-ci.org/opentracing-contrib/java-jdbi
[cov-img]: https://coveralls.io/repos/github/opentracing-contrib/java-jdbi/badge.svg?branch=master
[cov]: https://coveralls.io/github/opentracing-contrib/java-jdbi?branch=master
[maven-img]: https://img.shields.io/maven-central/v/io.opentracing.contrib/jdbi-opentracing.svg
[maven]: http://search.maven.org/#search%7Cga%7C1%7Cjdbi-opentracing
