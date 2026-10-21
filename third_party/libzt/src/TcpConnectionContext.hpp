#ifndef ZTS_TCP_CONNECTION_CONTEXT_HPP
#define ZTS_TCP_CONNECTION_CONTEXT_HPP

namespace ZeroTier {

// Phy can deliver an event without a live socket user context. Never dispatch
// TCP data or writable events against an absent TcpConnection; retire the socket
// so subsequent polls cannot deliver the same orphaned event again.
template <typename CloseSocket>
inline bool ensureTcpConnectionContext(void** uptr, CloseSocket closeSocket)
{
    if (uptr && *uptr) {
        return true;
    }
    closeSocket();
    return false;
}

} // namespace ZeroTier

#endif
