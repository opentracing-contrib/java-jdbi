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

```java
// Instantiate tracer
Tracer tracer = ...;

// Instatiate Jdbi
Jdbi dbi = Jdbi.create...;

// One time only: bind OpenTracing to the Jdbi instance as a SqlLogger.  
// OpentracingSqlLogger creates OpenTracing Spans for each Jdbi SqlStatement.
dbi.setSqlLogger(new OpentracingSqlLogger(tracer));
 
// Elsewhere, anywhere a `Handle` is available:
Handle handle = ...;
Span parentSpan = ...;  // optional
 
// Create statements as usual with your `handle` instance.
Query statement = handle.createQuery("SELECT COUNT(*) FROM accounts");
 
// If a parent Span is available, establish the relationship via setParent.
OpentracingSqlLogger.setParent(statement, parent);
 
// Use Jdbi as per usual, and Spans will be created for every SqlStatement automatically.
List<Map<String, Object>> results = statement.mapToMap().list();
```

### Older Jdbi3 versions

The `OpentracingSqlLogger` obviously implements `SqlLogger`.
This interface was introduced in JDBI version `3.2`.
In case you have an older version of Jdbi3, you should use the now-deprecated
`OpentracingTimingCollector` instead.  
Configuration and behaviour is the same as described above,
except setting the SqlLogger changes from `dbi.setSqlLogger(..)` to:
```java
dbi.setTimingCollector(new OpentracingTimingCollector(tracer));
``` 

## License

[Apache 2.0 License](./LICENSE).

[ci-img]: https://travis-ci.org/opentracing-contrib/java-jdbi.svg?branch=master
[ci]: https://travis-ci.org/opentracing-contrib/java-jdbi
[cov-img]: https://coveralls.io/repos/github/opentracing-contrib/java-jdbi/badge.svg?branch=master
[cov]: https://coveralls.io/github/opentracing-contrib/java-jdbi?branch=master
[maven-img]: https://img.shields.io/maven-central/v/io.opentracing.contrib/jdbi-opentracing.svg
[maven]: http://search.maven.org/#search%7Cga%7C1%7Cjdbi-opentracing
