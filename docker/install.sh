#!/bullseye/bin/sh

export PATH=/bullseye/usr/bin:/bullseye/bin:/bullseye/sbin

export LD_LIBRARY_PATH=/bullseye/lib/x86_64-linux-gnu:/bullseye/usr/lib/x86_64-linux-gnu/:/bullseye/lib64

# Install system libraries required for java
cp /java/lib/x86_64-linux-gnu/libz.so* /java/lib/x86_64-linux-gnu/libgcc_s.so* /lib/x86_64-linux-gnu/
cp /java/usr/lib/x86_64-linux-gnu/libstdc++.so* /usr/lib/x86_64-linux-gnu/

# Install java
cp -r /java/etc/java-11-openjdk /etc/
cp -r /java/etc/ssl/certs/java /etc/ssl/certs/
cp -r /java/usr/lib/jvm /usr/lib/

# Remove libs and executables from the base image we don't want
rm /usr/lib/x86_64-linux-gnu/libssl.so*
rm -rf /usr/bin
