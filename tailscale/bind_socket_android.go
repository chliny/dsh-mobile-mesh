//go:build android

package main

/*
#include <dlfcn.h>
#include <errno.h>
static int bindSocket(int fd) {
    typedef int (*bindSocketFunc)(int);
    static bindSocketFunc function;
    static int lookedUp;
    if (!lookedUp) { function = (bindSocketFunc)dlsym(RTLD_DEFAULT, "TailscaleBindSocket"); lookedUp = 1; }
    return function ? function(fd) : ENOSYS;
}
*/
import "C"

import "fmt"

func bindAndroidSocket(fd int) error {
	if code := C.bindSocket(C.int(fd)); code != 0 {
		return fmt.Errorf("Network.bindSocket(%d): errno=%d", fd, int(code))
	}
	return nil
}
