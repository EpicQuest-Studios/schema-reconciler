package model

import (
	"errors"
	"fmt"
)

// ErrInvalidArgument is the Go analog of the specification's
// IllegalArgumentException / IllegalArgumentError: an argument violated a
// method's contract (empty name, forbidden parent, negative order, unknown
// enum literal, malformed plan JSON). Constructor and validation errors
// produced by this package satisfy errors.Is(err, ErrInvalidArgument).
var ErrInvalidArgument = errors.New("invalid argument")

// invalidArgError carries a bare contract message while still matching
// ErrInvalidArgument via errors.Is.
type invalidArgError struct{ msg string }

func (e invalidArgError) Error() string { return e.msg }

func (e invalidArgError) Is(target error) bool { return target == ErrInvalidArgument }

// invalidArgf formats an argument-contract error (errors.Is -> ErrInvalidArgument).
func invalidArgf(format string, args ...any) error {
	return invalidArgError{msg: fmt.Sprintf(format, args...)}
}

// InvalidArgumentErrorf formats an argument-contract error for other packages
// of this module (errors.Is -> ErrInvalidArgument).
func InvalidArgumentErrorf(format string, args ...any) error {
	return invalidArgf(format, args...)
}
