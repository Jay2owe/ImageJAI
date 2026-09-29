"""Selectable data rows without a widget/layout tree for every item."""
from textual.widgets import OptionList


class NavigableOptionList(OptionList):
    @property
    def index(self):
        return self.highlighted

    @index.setter
    def index(self, value):
        self.highlighted = value

    def _skip_heading(self, direction):
        index = self.highlighted
        if index is None or not self.get_option_at_index(index).disabled:
            return
        for step in (direction, -direction):
            stop = self.option_count if step > 0 else -1
            for target in range(index, stop, step):
                if not self.get_option_at_index(target).disabled:
                    self.highlighted = target
                    return

    def action_page_down(self):
        super().action_page_down()
        if self.highlighted is None:
            self.action_last()
        self._skip_heading(1)

    def action_page_up(self):
        super().action_page_up()
        if self.highlighted is None:
            self.action_first()
        self._skip_heading(-1)
