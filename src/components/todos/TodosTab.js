import React from 'react';

export default function TodosTab({
  filteredTodos,
  todoCategories,
  activeTodoCategory,
  setActiveTodoCategory,
  newTodo,
  setNewTodo,
  todoCategoryDraft,
  setTodoCategoryDraft,
  todoCategoryUiError,
  setTodoCategoryUiError,
  todoCategoryAdding,
  setTodoCategoryAdding,
  confirmDeleteCategory,
  setConfirmDeleteCategory,
  onAddTodo,
  onToggleTodo,
  onDeleteTodo,
  onAddTodoCategory,
  onRequestRemoveCategory,
  onRemoveCategory,
}) {
  const visibleTodos = filteredTodos.filter(
    (t) => (t.category || 'General') === activeTodoCategory
  );

  return (
    <div className="todos-section">
      <div className="todo-category-tabs">
        {todoCategories.map((cat) => (
          <button
            key={cat}
            className={activeTodoCategory === cat ? 'active' : ''}
            onClick={() => setActiveTodoCategory(cat)}
            title={cat}
          >
            <span className="todo-cat-label">{cat}</span>
            {cat !== 'General' && cat !== 'Shopping List' && activeTodoCategory === cat && (
              <span
                className="todo-cat-remove"
                title="Remove category"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  onRequestRemoveCategory(cat);
                }}
              >
                −
              </span>
            )}
          </button>
        ))}
        <button
          className="todo-cat-add"
          onClick={(e) => {
            e.preventDefault();
            setTodoCategoryUiError('');
            setTodoCategoryAdding((v) => !v);
          }}
          title="Add category"
        >
          +
        </button>
      </div>

      {todoCategoryAdding && (
        <div className="todo-cat-add-row">
          <input
            className="todo-cat-input"
            type="text"
            placeholder="Enter category name"
            value={todoCategoryDraft}
            onChange={(e) => setTodoCategoryDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                onAddTodoCategory();
              }
              if (e.key === 'Escape') {
                setTodoCategoryAdding(false);
                setTodoCategoryDraft('');
                setTodoCategoryUiError('');
              }
            }}
          />
          <button className="todo-cat-confirm" type="button" onClick={onAddTodoCategory}>
            Add
          </button>
          <button
            className="todo-cat-cancel"
            type="button"
            onClick={() => {
              setTodoCategoryAdding(false);
              setTodoCategoryDraft('');
              setTodoCategoryUiError('');
            }}
          >
            Cancel
          </button>
        </div>
      )}
      {todoCategoryUiError && <div className="todo-cat-error">{todoCategoryUiError}</div>}

      {confirmDeleteCategory && (
        <div className="keep-modal-overlay" onClick={() => setConfirmDeleteCategory(null)}>
          <div className="keep-modal todo-confirm-modal" onClick={(e) => e.stopPropagation()}>
            <div className="todo-confirm-title">Delete category?</div>
            <div className="todo-confirm-body">
              <div>
                Category: <strong>{confirmDeleteCategory}</strong>
              </div>
              <div>Todos in this category will be moved to General.</div>
            </div>
            <div className="keep-modal-actions">
              <button className="cancel-btn" onClick={() => setConfirmDeleteCategory(null)}>
                Cancel
              </button>
              <button
                className="delete-btn"
                onClick={async () => {
                  const name = confirmDeleteCategory;
                  setConfirmDeleteCategory(null);
                  await onRemoveCategory(name);
                }}
              >
                OK
              </button>
            </div>
          </div>
        </div>
      )}

      <form onSubmit={onAddTodo} className="todo-form">
        <input
          type="text"
          placeholder={`Add to ${activeTodoCategory}...`}
          value={newTodo}
          onChange={(e) => setNewTodo(e.target.value)}
        />
        <button type="submit">Add</button>
      </form>

      <div className="todos-list">
        {visibleTodos.length === 0 ? (
          <p className="empty-state">Nothing in {activeTodoCategory} yet — add one above.</p>
        ) : (
          visibleTodos.map((todo) => (
            <div key={todo.id} className="todo-item">
              <input
                type="checkbox"
                checked={todo.completed}
                onChange={() => onToggleTodo(todo.id, todo.completed)}
              />
              <span className={todo.completed ? 'completed' : ''}>{todo.text}</span>
              <button onClick={() => onDeleteTodo(todo.id)} className="delete-btn" aria-label="Delete todo" title="Delete todo">🗑</button>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
