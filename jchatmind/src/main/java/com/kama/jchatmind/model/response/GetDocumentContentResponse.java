package com.kama.jchatmind.model.response;

/** Original Markdown text; storage metadata is deliberately not part of this response. */
public record GetDocumentContentResponse(String documentId, String filename, String content) { }
