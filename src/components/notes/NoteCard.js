import React from 'react';
import { renderWithLinks } from '../../utils/renderWithLinks';

export default function NoteCard({ note, onOpen, onTogglePin, onDelete }) {
  return (
    <div
      key={note.id}
      className={note.pinned ? 'keep-note-card pinned' : 'keep-note-card'}
      onClick={() => onOpen(note)}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(note); } }}
      role="button"
      tabIndex={0}
      aria-label={note.title || 'Untitled note'}
    >
      <div className="keep-note-card-top">
        <div className="keep-note-title">{note.title || ''}</div>
        <button
          className="keep-pin-btn"
          title={note.pinned ? 'Unpin note' : 'Pin note'}
          onClick={(e) => {
            e.stopPropagation();
            onTogglePin(note);
          }}
        >
          {note.pinned ? '📌' : '📍'}
        </button>
      </div>
      <div className="keep-note-snippet">
        {renderWithLinks((note.content || '').substring(0, 220))}
        {(note.content || '').length > 220 ? '…' : ''}
      </div>

      <button
        className="keep-trash-btn"
        title="Delete note"
        aria-label="Delete note"
        onClick={(e) => {
          e.stopPropagation();
          onDelete(note.id);
        }}
      >
        🗑
      </button>
    </div>
  );
}
