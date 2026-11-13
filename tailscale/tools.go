//go:build tools

// Mantiene golang.org/x/mobile/bind en go.mod: gomobile lo necesita para generar el AAR.
package tsbridge

import _ "golang.org/x/mobile/bind"
