import React from 'react';

export function renderWithLinks(text) {
  const input = text || '';
  const urlRe = /(https?:\/\/[^\s]+)/g;
  const parts = input.split(urlRe);

  return parts.map((part, idx) => {
    if (part.match(urlRe)) {
      const href = part;
      return (
        <a
          key={`u-${idx}`}
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          onClick={(e) => {
            // Prevent card click handlers from firing.
            e.stopPropagation();
          }}
        >
          {href}
        </a>
      );
    }
    return <React.Fragment key={`t-${idx}`}>{part}</React.Fragment>;
  });
}
