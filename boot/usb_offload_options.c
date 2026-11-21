// SPDX-License-Identifier: Apache-2.0
#include <endian.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

int main(void)
{
    uint32_t mode;
    ssize_t size;
    int fd = open("/proc/device-tree/chosen/atag,boot", O_RDONLY | O_CLOEXEC);

    if (fd < 0) {
        perror("USB offload boot mode");
        return 1;
    }

    /* MediaTek ATAG_BOOT: two header words followed by the boot mode. */
    do {
        size = pread(fd, &mode, sizeof(mode), 8);
    } while (size < 0 && errno == EINTR);
    close(fd);
    if (size != (ssize_t)sizeof(mode)) {
        fputs("USB offload: cannot read boot mode\n", stderr);
        return 1;
    }

    /* RECOVERY_BOOT = 2; other modes retain the driver's offload policy. */
    puts(le32toh(mode) == 2 ? "offload=0" : "offload=1");
    return 0;
}
