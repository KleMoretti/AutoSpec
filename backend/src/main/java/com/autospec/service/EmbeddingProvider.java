package com.autospec.service;

public interface EmbeddingProvider {
    String modelVersion();

    int dimensions();

    double[] embed(String text);
}
