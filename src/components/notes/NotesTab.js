import React from 'react';
import NoteCard from './NoteCard';
import { renderWithLinks } from '../../utils/renderWithLinks';
import { sortNotes } from '../../utils/sortNotes';

export default function NotesTab({
  filteredNotes,
  viewMode,
  noteSort,
  setNoteSort,
  composerOpen,
  setComposerOpen,
  composerNote,
  setComposerNote,
  currentNote,
  setCurrentNote,
  noteModalOpen,
  setNoteModalOpen,
  noteModalEditing,
  setNoteModalEditing,
  onCreateNote,
  onSaveNote,
  onTogglePin,
  onDeleteNote,
}) {
  const hasPinnedNotes = filteredNotes.some((n) => n.pinned);
  const hasOtherNotes = filteredNotes.some((n) => !n.pinned);

  const pinnedNotes = sortNotes(filteredNotes.filter((n) => n.pinned), noteSort);
  const otherNotes = sortNotes(filteredNotes.filter((n) => !n.pinned), noteSort);

  const openNote = (note) => {
    setCurrentNote(note);
    setNoteModalEditing(true);
    setNoteModalOpen(true);
  };

  const closeModal = () => {
    setNoteModalOpen(false);
    setNoteModalEditing(false);
    setCurrentNote({ id: null, title: '', content: '' });
  };

  return (
    <div className="keep-notes">
      <div className="keep-notes-toolbar">
        <div className="note-sort" onClick={(e) => e.stopPropagation()}>
          <span className="note-sort-label">Sort</span>
          <select
            className="note-sort-select"
            value={noteSort}
            onChange={(e) => setNoteSort(e.target.value)}
            aria-label="Sort notes"
            title="Sort notes"
          >
            <option value="dateDesc">Date ↓</option>
            <option value="dateAsc">Date ↑</option>
            <option value="alphaAsc">A → Z</option>
            <option value="alphaDesc">Z → A</option>
          </select>
        </div>
      </div>

      {/* Composer */}
      <div
        className={composerOpen ? 'keep-compose open' : 'keep-compose'}
        onClick={() => {
          setComposerOpen(true);
          setComposerNote({ title: '', content: '' });
        }}
        onKeyDown={(e) => {
          if (!composerOpen && (e.key === 'Enter' || e.key === ' ')) {
            e.preventDefault();
            setComposerOpen(true);
            setComposerNote({ title: '', content: '' });
          }
        }}
        role="button"
        tabIndex={0}
        aria-label="Create a new note"
        aria-expanded={composerOpen}
      >
        {!composerOpen ? (
          <div className="keep-compose-collapsed">Take a note…</div>
        ) : (
          <div className="keep-compose-expanded" onClick={(e) => e.stopPropagation()}>
            <input
              type="text"
              placeholder="Title"
              value={composerNote.title}
              onChange={(e) => setComposerNote({ ...composerNote, title: e.target.value })}
            />
            <textarea
              placeholder="Take a note…"
              value={composerNote.content}
              onChange={(e) => setComposerNote({ ...composerNote, content: e.target.value })}
            />
            <div className="keep-compose-actions">
              <button
                className="save-btn"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  onCreateNote(composerNote.title, composerNote.content);
                  setComposerNote({ title: '', content: '' });
                  setComposerOpen(false);
                }}
              >
                Save
              </button>
              <button
                className="cancel-btn"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  setComposerOpen(false);
                  setComposerNote({ title: '', content: '' });
                }}
              >
                Close
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Notes grid */}
      {filteredNotes.length === 0 ? (
        <p className="empty-state">No notes yet — take one above to get started.</p>
      ) : (
        <>
          {hasPinnedNotes && <div className="keep-section-label">Pinned</div>}
          <div className={viewMode === 'list' ? 'keep-notes-grid list' : 'keep-notes-grid'}>
            {(hasPinnedNotes ? pinnedNotes : sortNotes(filteredNotes, noteSort)).map((note) => (
              <NoteCard
                key={note.id}
                note={note}
                onOpen={openNote}
                onTogglePin={onTogglePin}
                onDelete={onDeleteNote}
              />
            ))}
          </div>

          {hasPinnedNotes && hasOtherNotes && (
            <>
              <div className="keep-section-label">Others</div>
              <div className={viewMode === 'list' ? 'keep-notes-grid list' : 'keep-notes-grid'}>
                {otherNotes.map((note) => (
                  <NoteCard
                    key={note.id}
                    note={note}
                    onOpen={openNote}
                    onTogglePin={onTogglePin}
                    onDelete={onDeleteNote}
                  />
                ))}
              </div>
            </>
          )}
        </>
      )}

      {/* Note modal */}
      {noteModalOpen && (
        <div className="keep-modal-overlay" onClick={closeModal}>
          <div className="keep-modal" onClick={(e) => e.stopPropagation()}>
            <div className="keep-modal-actions keep-modal-actions-top">
              <button
                className="save-btn"
                onClick={() => {
                  onSaveNote();
                  setNoteModalEditing(false);
                  setNoteModalOpen(false);
                }}
              >
                Save
              </button>
              <button className="cancel-btn" onClick={closeModal}>
                Close
              </button>
              {currentNote.id && (
                <button
                  className="delete-btn"
                  onClick={() => {
                    onDeleteNote(currentNote.id);
                    closeModal();
                  }}
                >
                  Delete
                </button>
              )}
            </div>

            <div className="keep-modal-body">
              {noteModalEditing ? (
                <>
                  <input
                    type="text"
                    placeholder="Title"
                    value={currentNote.title}
                    onChange={(e) => setCurrentNote({ ...currentNote, title: e.target.value })}
                  />
                  <textarea
                    className="keep-note-editor-textarea"
                    placeholder="Take a note…"
                    value={currentNote.content}
                    onChange={(e) => setCurrentNote({ ...currentNote, content: e.target.value })}
                  />
                </>
              ) : (
                <>
                  <div className="keep-note-modal-title">{currentNote.title || ''}</div>
                  <div className="keep-note-modal-content">{renderWithLinks(currentNote.content || '')}</div>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
