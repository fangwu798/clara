package com.clara.memory.semantic.milvus;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class MilvusCollectionSpec {
    private static final int SCHEMA_VERSION = 1;
    private static final Set<String> REQUIRED_FIELDS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "id", "tenant_id", "user_id", "conversation_id", "domain",
                    "source_type", "source_id", "content", "content_hash",
                    "created_at", "expires_at", "embedding_model",
                    "embedding_revision", "metadata", "vector")));

    private final String collection;
    private final String embeddingModel;
    private final String embeddingRevision;
    private final String distanceType;
    private final int dimension;
    private final Set<String> requiredFields;

    public MilvusCollectionSpec(String collection, String model, String revision,
            int dimension, String distance) {
        this(collection, model, revision, dimension, distance, REQUIRED_FIELDS);
    }

    public MilvusCollectionSpec(String collection, String model, String revision,
            int dimension, String distance, Set<String> requiredFields) {
        if (isBlank(collection) || isBlank(model) || isBlank(revision)
                || dimension <= 0 || isBlank(distance) || requiredFields == null
                || requiredFields.isEmpty() || requiredFields.contains(null)) {
            throw new IllegalArgumentException("Milvus collection specification is invalid");
        }
        LinkedHashSet<String> fields = new LinkedHashSet<String>();
        for (String field : requiredFields) {
            if (isBlank(field)) {
                throw new IllegalArgumentException("Milvus field names must not be blank");
            }
            fields.add(field);
        }
        this.collection = collection;
        this.embeddingModel = model;
        this.embeddingRevision = revision;
        this.dimension = dimension;
        this.distanceType = distance;
        this.requiredFields = Collections.unmodifiableSet(fields);
    }

    public int getSchemaVersion() { return SCHEMA_VERSION; }
    public String getCollection() { return collection; }
    public String getEmbeddingModel() { return embeddingModel; }
    public String getEmbeddingRevision() { return embeddingRevision; }
    public int getDimension() { return dimension; }
    public String getDistanceType() { return distanceType; }
    public Set<String> getRequiredFields() { return requiredFields; }

    public boolean sameIdentity(MilvusCollectionSpec other) {
        return other != null
                && SCHEMA_VERSION == other.getSchemaVersion()
                && collection.equals(other.collection)
                && embeddingModel.equals(other.embeddingModel)
                && embeddingRevision.equals(other.embeddingRevision)
                && dimension == other.dimension
                && distanceType.equals(other.distanceType)
                && requiredFields.equals(other.requiredFields);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
