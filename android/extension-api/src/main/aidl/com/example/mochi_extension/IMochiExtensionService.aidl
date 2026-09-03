package com.example.mochi_extension;

import com.example.mochi_extension.ExtensionConnectionState;
import com.example.mochi_extension.ExtensionMetadata;
import com.example.mochi_extension.ExtensionToolDefinition;
import com.example.mochi_extension.ExtensionToolRequest;
import com.example.mochi_extension.IMochiAttachmentCallback;
import com.example.mochi_extension.IMochiOperationCallback;
import com.example.mochi_extension.IMochiToolCallback;

interface IMochiExtensionService {
    ExtensionMetadata getMetadata();
    ExtensionConnectionState getConnectionState();
    List<ExtensionToolDefinition> listTools();
    oneway void callTool(
        in ExtensionToolRequest request,
        in IMochiToolCallback callback
    );
    oneway void cancelTool(String requestId);
    oneway void openAttachment(
        String attachmentId,
        in IMochiAttachmentCallback callback
    );
    oneway void disconnect(in IMochiOperationCallback callback);
}
