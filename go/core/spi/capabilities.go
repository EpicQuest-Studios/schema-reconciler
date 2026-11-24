package spi

import "github.com/EpicQuest-Studios/schema-reconciler/go/core/model"

// DialectCapabilities describes what a dialect supports (spec 6.1):
// whether its DDL is transactional, whether it can bind a session default
// namespace, and the object kinds it manages.
type DialectCapabilities struct {
	TransactionalDDL        bool
	SessionDefaultNamespace bool
	SupportedKinds          map[model.ObjectKind]struct{}
}

// NewDialectCapabilities builds capabilities with the supported kinds stored
// as a set.
func NewDialectCapabilities(transactionalDDL, sessionDefaultNamespace bool,
	supportedKinds ...model.ObjectKind) DialectCapabilities {
	kinds := make(map[model.ObjectKind]struct{}, len(supportedKinds))
	for _, k := range supportedKinds {
		kinds[k] = struct{}{}
	}
	return DialectCapabilities{
		TransactionalDDL:        transactionalDDL,
		SessionDefaultNamespace: sessionDefaultNamespace,
		SupportedKinds:          kinds,
	}
}

// SupportsKind reports whether the dialect manages the given object kind.
func (c DialectCapabilities) SupportsKind(k model.ObjectKind) bool {
	_, ok := c.SupportedKinds[k]
	return ok
}
