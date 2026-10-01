package cn.wayecai.launcher.bootanim;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import b.f;
import java.io.File;
import java.io.FileNotFoundException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/* loaded from: classes.dex */
public final class BootAnimProvider extends ContentProvider {

    /* renamed from: a, reason: collision with root package name */
    public static final Pattern f265a = Pattern.compile("BA\\d{8}[0-9A-F]{16}\\.zip");

    public final void a() {
        boolean z;
        String callingPackage = getCallingPackage();
        if (!"cn.wayecai.bootanim".equals(callingPackage)) {
            throw new SecurityException("只给开机动画安装器");
        }
        try {
            PackageManager packageManager = getContext().getPackageManager();
            String[] packagesForUid = packageManager.getPackagesForUid(Binder.getCallingUid());
            if (packagesForUid != null) {
                z = false;
                for (String str : packagesForUid) {
                    z |= callingPackage.equals(str);
                }
            } else {
                z = false;
            }
            if (!z) {
                throw new SecurityException("调用方对不上");
            }
            SigningInfo signingInfo = packageManager.getPackageInfo(callingPackage, 134217728).signingInfo;
            Signature[] apkContentsSigners = signingInfo == null ? null : signingInfo.getApkContentsSigners();
            if (apkContentsSigners == null || apkContentsSigners.length != 1) {
                throw new SecurityException("安装器签名不对");
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apkContentsSigners[0].toByteArray());
            StringBuilder sb = new StringBuilder(64);
            for (byte b2 : digest) {
                sb.append(String.format(Locale.US, "%02x", Byte.valueOf(b2)));
            }
            if (!"1145125856b50ea81b94338c841301a8fa0dcf0380087091967ee5bc94867d3d".equals(sb.toString())) {
                throw new SecurityException("安装器签名不对");
            }
        } catch (SecurityException e2) {
            throw e2;
        } catch (Exception unused) {
            throw new SecurityException("认不出调用方");
        }
    }

    public final File b(Uri uri) {
        String lastPathSegment = uri.getLastPathSegment();
        if (lastPathSegment == null || !f265a.matcher(lastPathSegment).matches() || uri.getPathSegments().size() != 1) {
            throw new FileNotFoundException("不认识的文件");
        }
        Context context = getContext();
        String[] strArr = f.f168a;
        File file = new File(context.getFilesDir(), "bootanim");
        if (!file.isDirectory()) {
            file.mkdirs();
        }
        File file2 = new File(file, lastPathSegment);
        if (file2.isFile()) {
            return file2;
        }
        throw new FileNotFoundException("文件不在了");
    }

    @Override // android.content.ContentProvider
    public final int delete(Uri uri, String str, String[] strArr) {
        throw new UnsupportedOperationException();
    }

    @Override // android.content.ContentProvider
    public final String getType(Uri uri) {
        return "application/zip";
    }

    @Override // android.content.ContentProvider
    public final Uri insert(Uri uri, ContentValues contentValues) {
        throw new UnsupportedOperationException();
    }

    @Override // android.content.ContentProvider
    public final boolean onCreate() {
        return true;
    }

    @Override // android.content.ContentProvider
    public final ParcelFileDescriptor openFile(Uri uri, String str) {
        if (!"r".equals(str)) {
            throw new SecurityException("只读");
        }
        a();
        return ParcelFileDescriptor.open(b(uri), 268435456);
    }

    @Override // android.content.ContentProvider
    public final Cursor query(Uri uri, String[] strArr, String str, String[] strArr2, String str2) {
        a();
        try {
            File b2 = b(uri);
            MatrixCursor matrixCursor = new MatrixCursor(new String[]{"_display_name", "_size"});
            matrixCursor.addRow(new Object[]{b2.getName(), Long.valueOf(b2.length())});
            return matrixCursor;
        } catch (FileNotFoundException unused) {
            return null;
        }
    }

    @Override // android.content.ContentProvider
    public final int update(Uri uri, ContentValues contentValues, String str, String[] strArr) {
        throw new UnsupportedOperationException();
    }
}
