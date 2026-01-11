#!/usr/bin/env bash
set -ex

update_zip() {
    echo "Updating $1"
    rm -fr z
    mkdir z
    pushd .
    cd z
    unzip "../$1"
    zip -d swt.jar  META-INF/*.RSA META-INF/*.DSA META-INF/*.SF
    zip "../$1" swt.jar
    popd
}

update_zip swt-4.38-cocoa-macosx-x86_64.zip
update_zip swt-4.38-gtk-linux-aarch64.zip
update_zip swt-4.38-gtk-linux-ppc64le.zip
update_zip swt-4.38-gtk-linux-riscv64.zip
update_zip swt-4.38-gtk-linux-x86_64.zip
update_zip swt-4.38-win32-win32-aarch64.zip
update_zip swt-4.38-win32-win32-x86_64.zip
