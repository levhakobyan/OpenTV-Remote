#!/usr/bin/env python3
"""Pure tests of lab IME semantics; no sockets, credentials or TV are used."""
import io
import json
import pathlib
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from fake_android_tv import IMEFixture, blob, integer, message, number, parse, safe_frame_log, text, utf16_replace, identity, http_handler, MAX_HTTP_BODY


def edit(ime, revision, start, end, value, operation=1):
    obj = integer(1, start) + integer(2, end) + text(3, value)
    record = integer(1, operation) + blob(2, obj)
    return blob(21, integer(1, ime) + integer(2, revision) + blob(3, record))


class Client:
    def __init__(self): self.frames = []
    def send_many(self, frames): self.frames.extend(frames)


class IMEFixtureTests(unittest.TestCase):
    def test_counterless_readback_is_explicit_manual_fallback_fixture(self):
        editor=IMEFixture();client=Client();editor.connect(client)
        editor.control('/open',dict(text='abc',ime=0,revision=42,omitCounters=True))
        self.assertEqual([20,22],[next(iter(parse(f))) for f in client.frames])
        self.assertTrue(editor.incoming(edit(0,42,3,3,'X')))
        self.assertEqual('abcX',editor.value)
        self.assertTrue(all(21 not in parse(f) for f in client.frames))

    def setUp(self):
        self.editor = IMEFixture()
        self.client = Client()
        self.editor.connect(self.client)
        self.editor.control('/open', dict(text='Հայ Привет 😀', start=0, end=0))
        self.client.frames.clear()

    def test_unicode_range_replacement_and_delete_use_utf16(self):
        self.assertEqual(utf16_replace('Հայ 😀 Привет', 4, 6, '🧑🏽\u200d💻'), 'Հայ 🧑🏽\u200d💻 Привет')
        self.assertTrue(self.editor.incoming(edit(7, 42, 0, 3, 'Բարեւ')))
        self.assertEqual(self.editor.state()['text'], 'Բարեւ Привет 😀')
        self.assertEqual(self.editor.revision, 43)
        end = len(self.editor.value.encode('utf-16-le')) // 2
        self.assertTrue(self.editor.incoming(edit(7, 43, end - 2, end, '')))
        self.assertEqual(self.editor.state()['text'], 'Բարեւ Привет ')
        self.assertEqual(self.editor.edit_count, 2)

    def test_spaces_paste_and_empty_field_are_real_edits(self):
        self.editor.control('/open', dict(text='abc', start=3, end=3))
        self.assertTrue(self.editor.incoming(edit(7, 42, 1, 2, '  🙂  ')))
        self.assertEqual(self.editor.value, 'a  🙂  c')
        self.assertEqual(self.editor.start, 7)
        end = len(self.editor.value.encode('utf-16-le')) // 2
        self.assertTrue(self.editor.incoming(edit(7, 43, 0, end, '')))
        self.assertEqual(self.editor.value, '')
        self.assertTrue(self.editor.incoming(edit(7, 44, 0, 0, ' ')))
        self.assertEqual(self.editor.value, ' ')

    def test_stale_counter_and_old_field_are_rejected(self):
        self.assertFalse(self.editor.incoming(edit(7, 41, 0, 0, 'old')))
        self.assertEqual(self.editor.edit_count, 0)
        self.editor.control('/field', dict(text='New field'))
        self.assertEqual(self.editor.context, 8)
        self.assertFalse(self.editor.incoming(edit(7, 42, 0, 0, 'old')))
        self.assertEqual(self.editor.value, 'New field')
        self.editor.control('/close', {})
        self.assertFalse(self.editor.incoming(edit(7, self.editor.revision, 0, 0, 'closed')))
        self.assertEqual(self.editor.state()['text'], '')
        self.assertEqual(self.editor.rejected_edits, 3)

    def test_half_surrogate_and_unsupported_op_never_modify_text(self):
        self.editor.control('/open', dict(text='a😀b', start=1, end=3))
        for start, end, operation in [(2, 2, 1), (1, 2, 1), (3, 8, 1), (1, 3, 0)]:
            self.assertFalse(self.editor.incoming(edit(7, 42, start, end, 'x', operation)))
        self.assertEqual(self.editor.value, 'a😀b')
        self.assertEqual(self.editor.revision, 42)
        with self.assertRaises((ValueError, UnicodeError)):
            self.editor.control('/remote', dict(text='😀', start=1, end=1))
        self.assertEqual(self.editor.value, 'a😀b')

    def test_open_ack_remote_stale_close_frame_shapes(self):
        self.editor.control('/open', dict(text='hello', start=1, end=4))
        self.assertEqual([next(iter(parse(raw))) for raw in self.client.frames], [20, 21, 22])
        status = message(message(parse(self.client.frames[0]), 20), 2)
        self.assertEqual(number(status, 1), 42)
        self.assertEqual(number(status, 3), 1)
        self.assertEqual(number(status, 4), 4)
        self.client.frames.clear()
        self.assertTrue(self.editor.incoming(edit(7, 42, 1, 4, 'i')))
        self.assertEqual([next(iter(parse(raw))) for raw in self.client.frames], [21, 20])
        self.assertEqual(number(message(message(parse(self.client.frames[-1]), 20), 1), 1), 7)
        self.editor.control('/remote', dict(text='TV update', start=2, end=2))
        current = self.client.frames[-1]
        self.editor.control('/stale', {})
        self.assertNotEqual(self.client.frames[-1], current)
        self.editor.control('/close', {})
        self.assertNotIn(2, message(parse(self.client.frames[-1]), 20))

    def test_delayed_ack_does_not_change_edit_target_or_replay_input(self):
        self.editor.control('/ack-pause', {})
        self.assertTrue(self.editor.incoming(edit(7, 42, 0, 3, 'Hello')))
        self.assertEqual(self.client.frames, [])
        self.assertEqual(self.editor.edit_count, 1)
        self.editor.control('/field', dict(text='Other'))
        self.client.frames.clear()
        self.editor.control('/ack-resume', {})
        self.assertEqual(len(self.client.frames), 2)
        self.assertEqual(self.editor.value, 'Other')
        self.assertEqual(self.editor.edit_count, 1)
        self.assertEqual(number(message(message(parse(self.client.frames[-1]), 20), 1), 1), 7)
        self.assertEqual(self.editor.context, 8)

    def test_ime_logging_never_contains_payload_bytes_hashes_or_lengths(self):
        secret = 'private Հայ пароль 😀'
        raw = edit(7, 42, 0, 0, secret)
        self.assertEqual(safe_frame_log(raw), 'IME EDIT received')
        self.assertEqual(safe_frame_log(blob(20, text(2, secret))), 'IME FRAME received')
        self.assertEqual(safe_frame_log(blob(3, raw)), 'IME FRAME received')
        self.assertEqual(safe_frame_log(blob(90, text(1, secret))), 'REMOTE FRAME received')
        self.assertEqual(safe_frame_log(b'\xff'), 'UNPARSED FRAME received')
        output = io.StringIO()
        with redirect_stdout(output), redirect_stderr(output):
            self.editor.control('/open', dict(text=secret))
            self.editor.incoming(edit(7, 42, 0, 0, secret))
            self.editor.control('/remote', dict(text=secret))
        self.assertEqual(output.getvalue(), '')
        key = blob(10, integer(1, 23) + integer(2, 1))
        self.assertEqual(safe_frame_log(key), 'REMOTE FRAME ' + key.hex())

    def test_http_body_limit_rejects_before_reading_and_errors_do_not_echo_input(self):
        handler_type = http_handler(self.editor)
        handler = object.__new__(handler_type)
        replies = []
        handler.reply = lambda status, payload: replies.append((status, payload))
        handler.headers = {'Content-Length': str(MAX_HTTP_BODY + 1)}
        handler.rfile = io.BytesIO(b'private content')
        handler.path = '/open'
        handler.close_connection = False
        handler.do_POST()
        self.assertEqual(replies, [(413, {'error': 'Request too large'})])
        self.assertEqual(handler.rfile.tell(), 0)
        self.assertTrue(handler.close_connection)
        replies.clear()
        handler.headers = {'Content-Length': '14'}
        handler.rfile = io.BytesIO(b'private secret')
        handler.do_POST()
        self.assertEqual(replies, [(400, {'error': 'Invalid editor control'})])

    def test_identity_reuses_existing_files_and_rejects_partial_pair(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / 'cert.pem').write_text('existing cert')
            with self.assertRaises(ValueError): identity(root)
            (root / 'key.pem').write_text('existing key')
            self.assertEqual(identity(root), (root / 'key.pem', root / 'cert.pem'))
            self.assertEqual((root / 'cert.pem').read_text(), 'existing cert')
            self.assertEqual((root / 'key.pem').read_text(), 'existing key')


if __name__ == '__main__': unittest.main()
