# Minimal HTTP proxy that only logs the destination host:port (CONNECT / plain requests) and tunnels bytes
import asyncio, sys, time
LOG = open(sys.argv[1], 'a', buffering=1)
async def pipe(r, w):
    try:
        while (d := await r.read(65536)): w.write(d); await w.drain()
    except Exception: pass
    finally:
        try: w.close()
        except Exception: pass
async def handle(cr, cw):
    try:
        head = await cr.readuntil(b'\r\n\r\n')
    except Exception:
        cw.close(); return
    line = head.split(b'\r\n')[0].decode('latin1')
    method, target, _ = line.split(' ', 2)
    if method == 'CONNECT':
        host, port = target.rsplit(':', 1)
    else:
        from urllib.parse import urlsplit
        u = urlsplit(target); host, port = u.hostname, u.port or 80
    LOG.write(f"{time.strftime('%H:%M:%S')} {method} {host}:{port}\n")
    try:
        sr, sw = await asyncio.open_connection(host, int(port))
    except Exception as e:
        LOG.write(f"  fallo {e}\n"); cw.close(); return
    if method == 'CONNECT':
        cw.write(b'HTTP/1.1 200 Connection established\r\n\r\n'); await cw.drain()
    else:
        sw.write(head)
    await asyncio.gather(pipe(cr, sw), pipe(sr, cw))
async def main():
    srv = await asyncio.start_server(handle, '127.0.0.1', 8899)
    async with srv: await srv.serve_forever()
asyncio.run(main())
