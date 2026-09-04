package com.example.mochi_extension;

import android.os.ParcelFileDescriptor;
import com.example.mochi_extension.ExtensionAttachmentDescriptor;

oneway interface IMochiAttachmentCallback {
    void onSuccess(
        in ExtensionAttachmentDescriptor descriptor,
        in ParcelFileDescriptor fileDescriptor
    );
    void onError(String attachmentId, String errorCode, String errorMessage);
}
