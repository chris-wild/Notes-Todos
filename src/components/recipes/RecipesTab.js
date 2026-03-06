import React from 'react';
import { useAuth } from '../../context/AuthContext';

export default function RecipesTab({
  recipes,
  filteredRecipes,
  recipeForm,
  setRecipeForm,
  recipePdfFile,
  setRecipePdfFile,
  viewingRecipe,
  setViewingRecipe,
  removePdf,
  setRemovePdf,
  recipeError,
  setRecipeError,
  recipeWorking,
  recipeWorkingText,
  features,
  isMobileDevice,
  onSaveRecipe,
  onDeleteRecipe,
  onEditRecipe,
  onViewRecipe,
  onCancelEdit,
  onCreateIngredientTodos,
}) {
  const { API_URL, token } = useAuth();

  const pdfUrl = (recipeId) =>
    `${(API_URL || '').startsWith('http') ? '/api' : API_URL}/recipes/${recipeId}/pdf?token=${encodeURIComponent(token)}`;

  return (
    <div className="recipes-section">
      <form className="recipe-editor" onSubmit={(e) => { e.preventDefault(); onSaveRecipe(); }}>
        <h2>{recipeForm.id ? 'Edit Recipe' : 'Add Recipe'}</h2>
        <input
          type="text"
          placeholder="Recipe name"
          value={recipeForm.name}
          onChange={(e) => setRecipeForm({ ...recipeForm, name: e.target.value })}
        />
        <textarea
          placeholder="Recipe notes (optional)..."
          value={recipeForm.notes}
          onChange={(e) => setRecipeForm({ ...recipeForm, notes: e.target.value })}
          rows={6}
        />
        <div className="pdf-upload-section">
          <label htmlFor="recipe-pdf-input" className="pdf-upload-label">
            Attach PDF or image (optional):
          </label>
          <input
            type="file"
            id="recipe-pdf-input"
            accept="application/pdf,image/*"
            onChange={(e) => {
              setRecipePdfFile(e.target.files[0] || null);
              setRemovePdf(false);
            }}
          />
          {recipeForm.id && !recipePdfFile && !removePdf && (
            <div className="existing-pdf-info">
              {recipes.find((r) => r.id === recipeForm.id)?.pdf_filename ? (
                <div className="pdf-attached-row">
                  <span className="pdf-attached-badge">
                    File attached: {recipes.find((r) => r.id === recipeForm.id)?.pdf_original_name}
                  </span>
                  <button
                    type="button"
                    className="remove-pdf-btn"
                    onClick={() => setRemovePdf(true)}
                  >
                    Remove PDF
                  </button>
                </div>
              ) : (
                <span className="no-pdf-badge">No PDF attached</span>
              )}
            </div>
          )}
          {removePdf && (
            <div className="pdf-removed-notice">
              PDF will be removed on save.{' '}
              <button type="button" onClick={() => setRemovePdf(false)} className="undo-remove-btn">
                Undo
              </button>
            </div>
          )}
        </div>
        {recipeError && <div className="recipe-error">{recipeError}</div>}
        <div className="editor-actions">
          <button type="submit" className="save-btn">
            {recipeForm.id ? 'Update Recipe' : 'Save Recipe'}
          </button>
          {recipeForm.id && (
            <button type="button" onClick={onCancelEdit} className="cancel-btn">
              Cancel
            </button>
          )}
        </div>
      </form>

      {recipeWorking && (
        <div className="working-overlay" role="status" aria-live="polite">
          <div className="working-modal" onClick={(e) => e.stopPropagation()}>
            <div className="working-spinner" aria-hidden="true" />
            <div className="working-text">{recipeWorkingText || 'Working…'}</div>
          </div>
        </div>
      )}

      <div className="recipe-detail-area">
        {viewingRecipe ? (
          <div className="recipe-viewer">
            <div className="recipe-viewer-header">
              <h2>{viewingRecipe.name}</h2>
              <div className="recipe-viewer-header-actions">
                {features.ingredientAutomation && (
                  <button
                    type="button"
                    className="pdf-view-btn ingredient-btn"
                    disabled={viewingRecipe._creatingIngredients}
                    onClick={() => onCreateIngredientTodos(viewingRecipe.id)}
                  >
                    Create ingredient list
                  </button>
                )}
                <button
                  className="close-viewer-btn"
                  onClick={() => setViewingRecipe(null)}
                >
                  Close
                </button>
              </div>
            </div>
            {viewingRecipe.notes && (
              <div className="recipe-viewer-notes">
                <p>{viewingRecipe.notes}</p>
              </div>
            )}
            <div className="recipe-pdf-viewer">
              <div className="pdf-viewer-actions">
                {viewingRecipe.pdf_filename ? (
                  <a
                    className="pdf-view-btn"
                    href={pdfUrl(viewingRecipe.id)}
                    target="_blank"
                    rel="noreferrer"
                  >
                    View PDF
                  </a>
                ) : null}
              </div>
              {viewingRecipe.pdf_filename ? (
                isMobileDevice ? null : (
                  <iframe
                    src={pdfUrl(viewingRecipe.id)}
                    title={`${viewingRecipe.name} PDF`}
                    className="pdf-iframe"
                  />
                )
              ) : (
                <p className="empty-state">No PDF attached to this recipe.</p>
              )}
            </div>
          </div>
        ) : (
          <div className="recipes-list">
            <h2>Your Recipes</h2>
            {filteredRecipes.length === 0 ? (
              <p className="empty-state">No recipes yet — add one using the form above.</p>
            ) : (
              filteredRecipes.map((recipe) => (
                <div key={recipe.id} className="recipe-item">
                  <div className="recipe-content" onClick={() => onViewRecipe(recipe)}>
                    <h3>{recipe.name}</h3>
                    <p>
                      {recipe.notes?.substring(0, 80)}{recipe.notes?.length > 80 ? '...' : ''}
                    </p>
                    <div className="recipe-meta">
                      {recipe.pdf_filename && (
                        <span className="recipe-has-pdf">PDF</span>
                      )}
                      <span className="recipe-date">
                        {new Date(recipe.updated_at || recipe.created_at).toLocaleDateString()}
                      </span>
                    </div>
                  </div>
                  <div className="recipe-actions">
                    <button
                      onClick={(e) => { e.stopPropagation(); onEditRecipe(recipe); }}
                      className="edit-btn"
                    >
                      Edit
                    </button>
                    <button
                      onClick={(e) => { e.stopPropagation(); onDeleteRecipe(recipe.id); }}
                      className="delete-btn"
                      aria-label="Delete recipe"
                      title="Delete recipe"
                    >
                      🗑
                    </button>
                  </div>
                </div>
              ))
            )}
          </div>
        )}
      </div>
    </div>
  );
}
