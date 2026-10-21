// Run with: clang++ -std=c++11 -Wall -Wextra -Werror
// third_party/libzt/test/TcpConnectionContextTest.cpp -o /path/to/test && /path/to/test
#include "../src/TcpConnectionContext.hpp"

#include <cassert>

int main()
{
    int closed = 0;
    const auto closeSocket = [&closed]() { ++closed; };

    // An orphaned TCP receive or writable event must retire its socket before
    // either callback can read the missing TcpConnection (the crash at 08:51).
    void* absent = nullptr;
    assert(!ZeroTier::ensureTcpConnectionContext(&absent, closeSocket));
    assert(closed == 1);
    assert(!ZeroTier::ensureTcpConnectionContext(nullptr, closeSocket));
    assert(closed == 2);

    // A live connection still follows the original data/write dispatch path.
    int connection = 1;
    void* present = &connection;
    assert(ZeroTier::ensureTcpConnectionContext(&present, closeSocket));
    assert(closed == 2);
    return 0;
}
