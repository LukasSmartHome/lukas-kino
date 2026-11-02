#!/bin/sh
# Liefert /share/kino aus (dort liegen web/ und System/Info/Public, siehe lg/README.md).
mkdir -p /share/kino 2>/dev/null
echo "Lukas Kino Web: Port 8098, Ordner /share/kino"
exec busybox-extras httpd -f -v -p 8098 -h /share/kino -c /etc/httpd.conf
