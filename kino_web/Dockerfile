ARG BUILD_FROM
FROM $BUILD_FROM

# busybox httpd: winzig, schickt keine X-Frame-Options (anders als Home Assistant),
# darum darf die LG-Jellyfin-App die Seite in ihr Vollbild-iframe laden.
RUN apk add --no-cache busybox-extras

COPY httpd.conf /etc/httpd.conf
COPY start.sh /start.sh
RUN chmod a+x /start.sh

CMD [ "/start.sh" ]
