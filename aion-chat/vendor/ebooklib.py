# ebooklib stub for Render
class Book:
    def __init__(self, *a, **k): pass
    def get_item(self, *a, **k): return None
    def get_items(self, *a, **k): return []
    def process_content(self, *a, **k): return []
class Epub:
    def __init__(self, *a, **k): self.book = Book()
    def process_content(self, *a, **k): pass
    def add_author(self, *a, **k): pass
    def add_item(self, *a, **k): pass
    def write(self, *a, **k): pass
epub = type("EpubModule", (), {"Book": Book, "Epub": Epub})()

