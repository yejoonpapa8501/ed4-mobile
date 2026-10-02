"""Index Korean text candidates in original map scripts without executing unknown opcodes.

Only reports CP949 literals after observed 0x0f text markers. Candidate offsets
are NOT NPC IDs or an event graph. Control bytes remain explicit for follow-up
VM analysis; output belongs to the user's local analysis, not the source repo.
"""
import argparse
import json
from pathlib import Path
from ed4_assets import archive, unpack_entry


def text_candidates(data):
    result = []
    for offset, byte in enumerate(data):
        if byte != 15:
            continue
        end = data.find(b'\0', offset + 1)
        if end < 0 or end - offset > 4096:
            continue
        raw = data[offset + 1:end]
        parts = []
        start = 0
        for i in range(len(raw) + 1):
            if i == len(raw) or raw[i] < 32:
                if i > start:
                    try:
                        text = raw[start:i].decode('cp949')
                    except UnicodeDecodeError:
                        parts = []
                        break
                    parts.append({'text': text})
                if i < len(raw):
                    parts.append({'control': raw[i]})
                start = i + 1
        literal = ''.join(p.get('text', '') for p in parts)
        if sum('\uac00' <= c <= '\ud7a3' for c in literal) >= 3:
            result.append({'offset': offset, 'end': end, 'parts': parts})
    return result


def inspect(root):
    scripts = []
    for path in sorted(root.glob('DATA_?.DAT')):
        for index, block in enumerate(archive(path)):
            try:
                data = unpack_entry(block)
            except ValueError:
                continue
            candidates = text_candidates(data)
            if candidates:
                scripts.append({'archive': path.name, 'resource': index,
                                'size': len(data), 'text_candidates': candidates})
    return {'format': 1, 'status': 'text candidates; control-flow unverified',
            'scripts': scripts}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('game_directory', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = inspect(args.game_directory)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f"Indexed {len(result['scripts'])} resources / "
          f"{sum(len(s['text_candidates']) for s in result['scripts'])} Korean text candidates")
