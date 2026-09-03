package com.example.mochi_extension;

import com.example.mochi_extension.ExtensionToolResult;

oneway interface IMochiToolCallback {
    void onResult(in ExtensionToolResult result);
}
