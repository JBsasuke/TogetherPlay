// Package tsbridge mete un nodo de Tailscale dentro de TogetherPlay (tsnet), sin la
// app de Tailscale ni permiso de VPN. Se compila con gomobile como AAR para Android.
//
// El relay de video escucha en 127.0.0.1; este paquete lo publica en la red de
// Tailscale (Serve) y, en el otro equipo, abre un puerto local que llega al relay
// remoto a través de Tailscale (Proxy).
package tsbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

var (
	mu      sync.Mutex
	srv     *tsnet.Server
	served  = map[int64]net.Listener{}
	proxies = map[string]net.Listener{}
	ports   = map[string]int64{}

	ifMu       sync.Mutex
	ifaces     []netmon.Interface
	registered bool

	logMu   sync.Mutex
	lastLog string
)

// SetInterfaces recibe las interfaces de red desde Android (Go no puede leerlas
// en Android 11 o más). Formato por línea: nombre|índice|mtu|flags|ip/prefijo,ip/prefijo
func SetInterfaces(desc string) {
	var list []netmon.Interface
	for _, line := range strings.Split(desc, "\n") {
		parts := strings.Split(strings.TrimSpace(line), "|")
		if len(parts) < 5 || parts[0] == "" {
			continue
		}
		idx, _ := strconv.Atoi(parts[1])
		mtu, _ := strconv.Atoi(parts[2])
		flagBits, _ := strconv.Atoi(parts[3])
		var addrs []net.Addr
		for _, a := range strings.Split(parts[4], ",") {
			if a == "" {
				continue
			}
			if _, ipnet, err := net.ParseCIDR(a); err == nil {
				ip, _, _ := net.ParseCIDR(a)
				addrs = append(addrs, &net.IPNet{IP: ip, Mask: ipnet.Mask})
			}
		}
		list = append(list, netmon.Interface{
			Interface: &net.Interface{Index: idx, MTU: mtu, Name: parts[0], Flags: net.Flags(flagBits)},
			AltAddrs:  addrs,
		})
	}
	ifMu.Lock()
	ifaces = list
	first := !registered
	registered = true
	ifMu.Unlock()
	if first {
		netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
			ifMu.Lock()
			defer ifMu.Unlock()
			out := make([]netmon.Interface, len(ifaces))
			copy(out, ifaces)
			return out, nil
		})
	}
}

// SetCrashFile hace que, si la parte de Go falla, el detalle quede en ese archivo.
func SetCrashFile(path string) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return
	}
	debug.SetCrashOutput(f, debug.CrashOptions{})
	f.Close()
}

// Start arranca el nodo. Si no está vinculado, Status() devolverá un enlace de inicio de sesión.
func Start(dir, hostname, authKey string) (err error) {
	defer recoverInto(&err)
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return nil
	}
	// Sin envío de registros a los servidores de Tailscale.
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	s := &tsnet.Server{
		Dir:      dir,
		Hostname: hostname,
		AuthKey:  authKey,
		Logf:     func(string, ...any) {},
		UserLogf: func(format string, args ...any) {
			logMu.Lock()
			lastLog = fmt.Sprintf(format, args...)
			logMu.Unlock()
		},
	}
	if err := s.Start(); err != nil {
		return err
	}
	srv = s
	return nil
}

// Status devuelve JSON: state (NeedsLogin, Starting, Running...), authURL, ip, name, tailnet, log.
func Status() (result string) {
	defer func() {
		if r := recover(); r != nil {
			result = fmt.Sprintf(`{"state":"Error","error":%q}`, fmt.Sprint(r))
		}
	}()
	mu.Lock()
	s := srv
	mu.Unlock()
	out := map[string]any{"state": "Stopped"}
	if s != nil {
		out["state"] = "Starting"
		if lc, err := s.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			st, err := lc.Status(ctx)
			cancel()
			if err == nil && st != nil {
				out["state"] = st.BackendState
				out["authURL"] = st.AuthURL
				for _, ip := range st.TailscaleIPs {
					if ip.Is4() {
						out["ip"] = ip.String()
						break
					}
				}
				if st.Self != nil {
					out["name"] = strings.TrimSuffix(st.Self.DNSName, ".")
				}
				if st.CurrentTailnet != nil {
					out["tailnet"] = st.CurrentTailnet.Name
				}
			} else if err != nil {
				out["error"] = err.Error()
			}
		}
	}
	logMu.Lock()
	out["log"] = lastLog
	logMu.Unlock()
	b, _ := json.Marshal(out)
	return string(b)
}

// Serve publica en Tailscale el puerto local indicado (el relay de video).
func Serve(port int64) (err error) {
	defer recoverInto(&err)
	mu.Lock()
	defer mu.Unlock()
	if srv == nil {
		return errors.New("tailscale integrado no iniciado")
	}
	if _, ok := served[port]; ok {
		return nil
	}
	ln, err := srv.Listen("tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		return err
	}
	served[port] = ln
	local := fmt.Sprintf("127.0.0.1:%d", port)
	go acceptLoop(ln, func() (net.Conn, error) { return net.DialTimeout("tcp", local, 5*time.Second) })
	return nil
}

// Proxy abre un puerto local que llega a remote (ip:puerto de Tailscale). Devuelve el puerto.
func Proxy(remote string) (port int64, err error) {
	defer recoverInto(&err)
	mu.Lock()
	defer mu.Unlock()
	if srv == nil {
		return 0, errors.New("tailscale integrado no iniciado")
	}
	if p, ok := ports[remote]; ok {
		return p, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	s := srv
	go acceptLoop(ln, func() (net.Conn, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		return s.Dial(ctx, "tcp", remote)
	})
	port = int64(ln.Addr().(*net.TCPAddr).Port)
	proxies[remote] = ln
	ports[remote] = port
	return port, nil
}

// recoverInto convierte un fallo de Go en un error normal, para que no cierre la app.
func recoverInto(err *error) {
	if r := recover(); r != nil {
		*err = fmt.Errorf("fallo interno de Tailscale: %v", r)
	}
}

func acceptLoop(ln net.Listener, dial func() (net.Conn, error)) {
	defer func() { recover() }()
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go func(in net.Conn) {
			defer func() { recover() }()
			out, err := dial()
			if err != nil {
				in.Close()
				return
			}
			done := make(chan struct{}, 2)
			go func() { io.Copy(out, in); done <- struct{}{} }()
			go func() { io.Copy(in, out); done <- struct{}{} }()
			<-done
			in.Close()
			out.Close()
		}(c)
	}
}

// Logout desvincula este equipo de la cuenta de Tailscale y detiene el nodo.
func Logout() error {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return nil
	}
	if lc, err := s.LocalClient(); err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		lc.Logout(ctx)
		cancel()
	}
	Stop()
	return nil
}

// Stop detiene el nodo sin desvincularlo.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	for k, ln := range served {
		ln.Close()
		delete(served, k)
	}
	for k, ln := range proxies {
		ln.Close()
		delete(proxies, k)
		delete(ports, k)
	}
	if srv != nil {
		srv.Close()
		srv = nil
	}
}
