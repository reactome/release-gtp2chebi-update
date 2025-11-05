# Guide to Pharmacology (GtoP) Cross-Referencer to ChEBI

This tool will perform an update to ReferenceTherapeutic instances (representing Guide to Pharmacology identifiers) by adding cross-references to ChEBI identifiers.

It will:

 - Download mapping files for Guide to Pharmacology ligands (GtoP to PubChem mapping) and PubChem synonyms (PubChem to ChEBI mapping)
 - Process the mapping files to build a GtoP to ChEBI mapping
 - Create and insert a ChEBI cross-reference to the ReferenceTherapeutics that have a GtoP identifier in the mapping

## Configuration

This application requires a properties file at `src/main/resources/config.properties` that looks like this (with the appropriate values filled in place of the placeholders):

```
user=username
pass=password
host=localhost
dbName=gk_central
```

## Compiling & Running

This is a Java application which requries a Java 8+ environment. You will need maven and a full JDK to compile.

To compile the application, run this command:

```
$ mvn clean package
```

To run the program, execute this command:
```
$ java -jar target/gtp_chebi_mapping-jar-with-dependencies.jar
```
