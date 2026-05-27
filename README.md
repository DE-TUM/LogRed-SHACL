# LogRed-SHACL

<div>
   <img src="https://img.shields.io/badge/java-21+-blue">
   <img src="https://img.shields.io/badge/build-maven-orange">
   <img src="https://img.shields.io/badge/license-MIT-green">
</div>

# Project overview

### Project Name
LogRed-SHACL — *Logical Redundancy Reduction in Shape Constraints for
Knowledge Graph Validation*.

### Project Purpose and Goals
Large SHACL shape schemas — produced by shape miners, extracted from
ontologies, or assembled from reusable templates — tend to accumulate
logically redundant constraints. A validator faithfully evaluates each of
them, inflating validation cost without changing the conformance outcome.

LogRed-SHACL takes a SHACL shape schema, lifts its constraints into a
symbolic logical representation, and applies Boolean simplification together
with SHACL-specific rules that merge same-predicate constraints and remove
redundant or contradictory conditions. Pattern-based caching and parallelism
let it scale to schemas with millions of constraints. The output is a
reduced shape schema that any existing SHACL validator can consume.

### Paper
Jin Ke, Antoon Bronselaer, Maribel Acosta. *Logical Redundancy Reduction in
Shape Constraints for Knowledge Graph Validation.* PVLDB, submitted.
doi: *(to be added on publication)*

# Table of Contents
1. [Installation Instructions](#installation-instructions)
2. [How to use](#how-to-use)
3. [Testing](#testing)
4. [Licensing](#licensing)
5. [Acknowledgements](#acknowledgements)
6. [Contact Information](#contact-information)

# Installation Instructions

### Pre-built jar
Download `logred-shacl-<version>.jar` from the
[releases page](https://github.com/DE-TUM/LogRed-SHACL/releases).
Requires Java 21 or newer.

### Build from source
```
git clone https://github.com/DE-TUM/LogRed-SHACL.git
cd LogRed-SHACL
mvn package
```
The jar is produced at `target/logred-shacl-0.1.0.jar`.
Requires Maven 3.6+.

# How to use

```
java -jar logred-shacl-0.1.0.jar <shapes.ttl>
```

The simplified output is written to `<shapes>_simplified.ttl`. Common options:

| Option | Description |
| --- | --- |
| `-o <path>` | Output file path. |
| `--format <fmt>` | `ntriples` (default), `turtle-flat`, `turtle-pretty`, `turtle-blocks`. |
| `--parallel` `-j <n>` | Parallel reduction with `n` worker threads. |
| `--stats` | Print parse / reduce / serialize timings. |
| `-h` | Show all options. |

Example:
```
java -jar logred-shacl-0.1.0.jar shapes.ttl \
    -o shapes_reduced.ttl --parallel -j 4 --stats
```

# Testing
```
mvn test
```

# Licensing
MIT — see [LICENSE.txt](LICENSE.txt).

# Acknowledgements
Built on [Apache Jena](https://jena.apache.org/) (RDF/SHACL I/O) and
[LogicNG](https://github.com/logic-ng/LogicNG) (Boolean formula simplification).
Joint work between TU Munich (Chair of Data Engineering) and Ghent University.

# Contact Information
### jin.ke@tum.de
### Maintainer: Jin Ke
