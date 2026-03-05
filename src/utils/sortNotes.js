export function sortNotes(list, noteSort) {
  const arr = Array.isArray(list) ? [...list] : [];

  const norm = (s) => (s || '').toString().trim().toLowerCase();
  const noteTime = (n) => {
    const v = n?.updated_at || n?.created_at;
    const t = v ? Date.parse(v) : NaN;
    return Number.isFinite(t) ? t : 0;
  };

  arr.sort((a, b) => {
    if (noteSort === 'alphaAsc' || noteSort === 'alphaDesc') {
      const at = norm(a?.title);
      const bt = norm(b?.title);
      const cmp = at.localeCompare(bt);
      if (cmp !== 0) return noteSort === 'alphaAsc' ? cmp : -cmp;
      // tiebreaker: most recently updated first
      return noteTime(b) - noteTime(a);
    }

    // date sorting
    const diff = noteTime(a) - noteTime(b);
    if (diff !== 0) return noteSort === 'dateAsc' ? diff : -diff;

    // tiebreaker: title
    return norm(a?.title).localeCompare(norm(b?.title));
  });

  return arr;
}
