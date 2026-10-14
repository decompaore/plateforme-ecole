/** Styles communs aux écrans des besoins et des dotations (v0.48). */
export const STYLES_MOYENS = `
  .bascule {
    display: inline-flex;
    flex-wrap: wrap;
    border: 1px solid var(--bordure);
    border-radius: var(--rayon);
    padding: 0.2rem;
    background: var(--surface);
    margin-bottom: 0.75rem;
  }
  .bascule button {
    min-height: 38px;
    padding: 0 0.8rem;
    border: none;
    background: none;
    border-radius: calc(var(--rayon) - 4px);
    font: inherit;
    font-weight: 600;
    color: var(--texte-doux);
    cursor: pointer;
  }
  .bascule button.actif {
    background: var(--primaire);
    color: var(--sur-primaire);
  }
  .barre-outils {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.5rem 0.75rem;
    margin-bottom: 0.75rem;
  }
  .espace {
    flex: 1;
  }
  .intertitre td {
    background: var(--surface);
    font-weight: 700;
  }
  .petit-texte {
    font-size: 0.85rem;
  }
  .chiffres {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem 1.5rem;
    margin: 0 0 1rem;
  }
  .chiffres div {
    display: flex;
    flex-direction: column;
  }
  .chiffres strong {
    font-size: 1.4rem;
  }
  td input[type='number'] {
    width: 6.5rem;
  }
  td input[type='text'] {
    min-width: 9rem;
  }
  textarea {
    width: 100%;
  }
`;
