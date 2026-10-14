package com.kama.jchatmind.service;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    /** Index and query a knowledge base with the same bound embedding model. */
    float[] embed(String kbId, String text);

    List<String> similaritySearch(String kbId, String title);
}
