(() => {
  const board = document.querySelector('.board');
  if (!board) return;
  const feedback = document.querySelector('.board-feedback');
  const columns = [...board.querySelectorAll('.column')];
  const cards = [...board.querySelectorAll('.ticket-card')];
  let dragged = null;
  let busy = false;
  let needsReload = false;

  function refresh() {
    for (const column of columns) {
      const count = column.querySelectorAll('.ticket-card').length;
      column.querySelector('.count').textContent = count;
      column.querySelector('.column-empty').hidden = count !== 0;
    }
    for (const card of cards) {
      card.draggable = !busy && !needsReload;
      const select = card.querySelector('.ticket-move');
      select.disabled = busy || needsReload;
      for (const option of select.options) {
        option.disabled = option.value === card.parentElement.dataset.status;
      }
    }
  }

  function clearDrag() {
    dragged?.classList.remove('dragging');
    dragged = null;
    columns.forEach(column => column.classList.remove('drop-target'));
  }

  async function move(card, column) {
    if (busy || needsReload || card.parentElement === column) return;
    busy = true;
    board.setAttribute('aria-busy', 'true');
    feedback.classList.remove('error');
    feedback.textContent = `Saving DK-${card.dataset.ticketId}…`;
    refresh();
    try {
      const response = await fetch(card.dataset.moveUrl, {
        method: 'POST',
        headers: { 'Accept': 'application/json' },
        body: new URLSearchParams({
          _csrf: board.dataset.csrf,
          version: card.dataset.version,
          status: column.dataset.status
        })
      });
      if (!response.ok) {
        if (response.status === 409) throw new Error('This ticket changed in another tab.');
        if (response.status === 403) throw new Error('Your session changed.');
        throw new Error('The move could not be saved.');
      }
      const ticket = await response.json();
      card.dataset.version = ticket.version;
      // Keep the board's existing newest-first order within each column.
      const following = [...column.querySelectorAll('.ticket-card')]
        .find(other => Number(other.dataset.ticketId) < Number(card.dataset.ticketId));
      column.insertBefore(card, following || column.querySelector('.column-empty'));
      card.querySelector('.ticket-move').focus({ preventScroll: true });
      feedback.textContent = `DK-${card.dataset.ticketId} moved to ${column.querySelector('h3').textContent.trim()}.`;
    } catch (error) {
      needsReload = true;
      feedback.classList.add('error');
      feedback.textContent = `${error.message} Reload the board before trying again. `;
      const reload = document.createElement('a');
      reload.href = window.location.href;
      reload.textContent = 'Reload board';
      feedback.append(reload);
    } finally {
      busy = false;
      board.removeAttribute('aria-busy');
      refresh();
    }
  }

  for (const card of cards) {
    const select = card.querySelector('.ticket-move');
    select.hidden = false;
    select.addEventListener('change', () => {
      const column = columns.find(column => column.dataset.status === select.value);
      select.value = '';
      if (column) move(card, column);
    });
    card.addEventListener('dragstart', event => {
      if (busy || needsReload || event.target.closest('select')) {
        event.preventDefault();
        return;
      }
      dragged = card;
      card.classList.add('dragging');
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', card.dataset.ticketId);
    });
    card.addEventListener('dragend', clearDrag);
    card.addEventListener('click', event => {
      if (dragged) event.preventDefault();
    });
  }

  for (const column of columns) {
    column.addEventListener('dragover', event => {
      if (!dragged || busy || dragged.parentElement === column) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = 'move';
      columns.forEach(other => other.classList.toggle('drop-target', other === column));
    });
    column.addEventListener('dragleave', event => {
      if (!column.contains(event.relatedTarget)) column.classList.remove('drop-target');
    });
    column.addEventListener('drop', event => {
      if (!dragged) return;
      event.preventDefault();
      const card = dragged;
      clearDrag();
      move(card, column);
    });
  }
  refresh();
})();
