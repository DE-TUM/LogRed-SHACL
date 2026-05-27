package model;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * Shared SHACL vocabulary constants.
 * Centralizes the SHACL namespace URI and commonly used Property/Resource instances
 * to avoid redeclaration across parser, serializer, and reducer.
 */
public final class ShaclVocabulary {
    private ShaclVocabulary() {} // Utility class

    /** SHACL namespace URI */
    public static final String SH = "http://www.w3.org/ns/shacl#";

    // --- SHACL Properties ---

    // Target properties
    public static final Property SH_TARGET_CLASS = prop("targetClass");
    public static final Property SH_TARGET_NODE = prop("targetNode");
    public static final Property SH_TARGET_SUBJECTS_OF = prop("targetSubjectsOf");
    public static final Property SH_TARGET_OBJECTS_OF = prop("targetObjectsOf");

    // Structural properties
    public static final Property SH_PROPERTY = prop("property");
    public static final Property SH_PATH = prop("path");

    // Cardinality constraints
    public static final Property SH_MIN_COUNT = prop("minCount");
    public static final Property SH_MAX_COUNT = prop("maxCount");

    // Type constraints
    public static final Property SH_DATATYPE = prop("datatype");
    public static final Property SH_CLASS = prop("class");
    public static final Property SH_NODE_KIND = prop("nodeKind");
    public static final Property SH_NODE_KIND_WRONG = prop("NodeKind"); // common typo

    // String constraints
    public static final Property SH_MIN_LENGTH = prop("minLength");
    public static final Property SH_MAX_LENGTH = prop("maxLength");
    public static final Property SH_PATTERN = prop("pattern");
    public static final Property SH_FLAGS = prop("flags");

    // Value constraints
    public static final Property SH_IN = prop("in");
    public static final Property SH_HAS_VALUE = prop("hasValue");
    public static final Property SH_LANGUAGE_IN = prop("languageIn");
    public static final Property SH_UNIQUE_LANG = prop("uniqueLang");

    // Numeric range constraints
    public static final Property SH_MIN_INCLUSIVE = prop("minInclusive");
    public static final Property SH_MAX_INCLUSIVE = prop("maxInclusive");
    public static final Property SH_MIN_EXCLUSIVE = prop("minExclusive");
    public static final Property SH_MAX_EXCLUSIVE = prop("maxExclusive");

    // Logical operators
    public static final Property SH_NOT = prop("not");
    public static final Property SH_AND = prop("and");
    public static final Property SH_OR = prop("or");
    public static final Property SH_XONE = prop("xone");

    // Reference constraints
    public static final Property SH_NODE = prop("node");
    public static final Property SH_EQUALS = prop("equals");
    public static final Property SH_DISJOINT = prop("disjoint");
    public static final Property SH_LESS_THAN = prop("lessThan");
    public static final Property SH_LESS_THAN_OR_EQUALS = prop("lessThanOrEquals");

    // Qualified value constraints
    public static final Property SH_QUALIFIED_VALUE_SHAPE = prop("qualifiedValueShape");
    public static final Property SH_QUALIFIED_MIN_COUNT = prop("qualifiedMinCount");
    public static final Property SH_QUALIFIED_MAX_COUNT = prop("qualifiedMaxCount");
    public static final Property SH_QUALIFIED_VALUE_SHAPES_DISJOINT = prop("qualifiedValueShapesDisjoint");

    // Closed shape constraints
    public static final Property SH_CLOSED = prop("closed");
    public static final Property SH_IGNORED_PROPERTIES = prop("ignoredProperties");

    // Magic Sets extension (non-standard, used by MagicShapes.jar)
    public static final Property SH_HAS_CLASS = prop("hasClass");

    // --- SHACL Resources ---
    public static final Resource SH_NODE_SHAPE = ResourceFactory.createResource(SH + "NodeShape");
    public static final Resource SH_PROPERTY_SHAPE = ResourceFactory.createResource(SH + "PropertyShape");

    private static Property prop(String localName) {
        return ResourceFactory.createProperty(SH, localName);
    }
}
