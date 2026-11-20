#!/usr/bin/env python3
"""把 aapt2 生成的 base.apk、classes.dex、assets、jniLibs 合成一个 APK。

大模型文件必须以 STORED 方式放入（不压缩），这样 AssetManager 可以直接
把它映射给 onnxruntime，避免解压到内存。
"""
import os
import sys
import zipfile

ZIP_STORED = zipfile.ZIP_STORED
ZIP_DEFLATED = zipfile.ZIP_DEFLATED


def add_path(zf, full, arc, compress):
    zf.write(full, arc, compress_type=compress)


def main():
    if len(sys.argv) != 6:
        print("usage: assemble.py base.apk out.apk classes.dex assets_dir jni_dir",
              file=sys.stderr)
        sys.exit(2)
    base, out, dex, assets_dir, jni_dir = sys.argv[1:]

    with zipfile.ZipFile(base) as zin, \
            zipfile.ZipFile(out, "w", allowZip64=True) as zout:
        infos = zin.infolist()
        names = [i.filename for i in infos]
        order = (["AndroidManifest.xml"] if "AndroidManifest.xml" in names else [])
        order += [n for n in names if n != "AndroidManifest.xml"]

        for name in order:
            info = zin.getinfo(name)
            data = zin.read(name)
            zinfo = zipfile.ZipInfo(name, info.date_time)
            zinfo.external_attr = info.external_attr
            zinfo.internal_attr = info.internal_attr
            zinfo.compress_type = (ZIP_STORED if name == "resources.arsc"
                                   else info.compress_type)
            zout.writestr(zinfo, data)

        add_path(zout, dex, "classes.dex", ZIP_DEFLATED)

        for root, _dirs, files in os.walk(assets_dir):
            for f in sorted(files):
                full = os.path.join(root, f)
                rel = os.path.relpath(full, assets_dir).replace(os.sep, "/")
                compress = ZIP_STORED if f.endswith((".onnx", ".bin", ".so")) else ZIP_DEFLATED
                add_path(zout, full, "assets/" + rel, compress)

        for f in sorted(os.listdir(jni_dir)):
            add_path(zout, os.path.join(jni_dir, f), "lib/arm64-v8a/" + f, ZIP_STORED)

    print("assembled %s (%.1f MB)" % (out, os.path.getsize(out) / 1024.0 / 1024.0))


if __name__ == "__main__":
    main()
