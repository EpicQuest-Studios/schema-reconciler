package model

// RenameHint is an explicit rename hint (spec 5.5, the directive
// `-- @reconciler rename <KIND> [<parent>.]<old_name> -> <new_name>`): when
// From exists in the actual snapshot, To does not, To is desired and From is
// not, the planner emits ALTER ... RENAME instead of a drop+create.
type RenameHint struct {
	From ObjectId
	To   ObjectId
}

// NewRenameHint validates that both ends have the same kind.
func NewRenameHint(from, to ObjectId) (RenameHint, error) {
	if err := from.Validate(); err != nil {
		return RenameHint{}, err
	}
	if err := to.Validate(); err != nil {
		return RenameHint{}, err
	}
	if from.Kind != to.Kind {
		return RenameHint{}, invalidArgf("rename kinds differ: %s -> %s", from, to)
	}
	return RenameHint{From: from, To: to}, nil
}
