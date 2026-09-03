package com.example.mochi_extension;

oneway interface IMochiOperationCallback {
    void onSuccess();
    void onError(String errorCode, String errorMessage);
}
