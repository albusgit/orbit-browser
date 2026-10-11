# אלוף המקראות (Wear OS)

An endless multiple-choice quiz for the round watch, for practising the 12 parts of
**מקראות ישראל** (the Bahad 1 entrance material). It is a separate app from Orbit
(`com.albustech.mikraot`), standalone, and needs no phone or network.

![Part picker, an answer lit, two wrong picks, a right answer, a long question lit and answered](screens.png)

## Using it

**Pick a part.** Turn the bezel (or swipe up/down) to move through the parts. The current part
fills the middle with its mastery bar; its neighbours peek above and below. Tap to start.
"כל השערים" at the top mixes every question.

**Answer.**
- **Bezel**: one click moves to the next answer. The lit answer opens to its full text; the
  others stay one line, so all answers fit.
- **Tap**: confirms the lit answer. Tapping a pill lights it; tapping it again answers.
- **Wrong**: your pick turns red with an ✗, the watch buzzes, and you pick again. The right
  answer is not revealed, and the bezel skips the answers already marked wrong.
- **Right**: the pill turns green with a ✓ and the next question comes after a moment (or at
  once with a tap or a bezel click). Only the right answer passes a question.
- **Long press**: stats for the part (mastered, first-try accuracy, best streak) and a two-tap
  reset.
- **Swipe right or Back**: back to the part picker.

Text is sized once per question so that the worst case fits: the question plus all answers
with the longest one open. When a long answer can't fit even at the smallest size, the
question shrinks to two lines while an answer is lit.

## Learning loop

Each part keeps its own progress. Each round asks every question once, with the ones not yet
mastered first. A question counts as right only if the first pick was right; otherwise it
returns after three other questions. Two right answers in a row mark a question as mastered.
Progress is saved after every answer.

## Questions

917 questions in `src/main/assets/questions.json`:

| # | Part | Questions | Source |
|---|---|---|---|
| 1 | ישראל: תעודת זהות | 171 | bhdone (103) + Mikraot Israel (68) |
| 2 | הפסיפס הישראלי | 93 | Mikraot Israel |
| 3 | תולדות עם ישראל | 51 | Mikraot Israel |
| 4 | בימי השואה | 50 | Mikraot Israel |
| 5 | בדרך למדינה | 77 | Mikraot Israel |
| 6 | סיפורה של המדינה | 111 | Mikraot Israel |
| 7 | ביטחון ישראל | 143 | Mikraot Israel |
| 8 | עזרה ראשונה | 44 | written (40) + Wayground (4) |
| 9 | נשק | 38 | Wayground |
| 10 | קשר | 39 | written (34) + Wayground (5) |
| 11 | תחקיר | 34 | written |
| 12 | טופוגרפיה | 66 | written (51) + Wayground (15) |

Sources:
- **Mikraot Israel**: the public Wayground (Quizizz) quizzes by the "Mikraot Israel" account:
  שאלון שער 1, שאלון מעורב 3 and שאלון מעורב 4. They mix the history and civics parts; each
  question was sorted into its part. Questions with a doubtful answer key, a duplicate, an image,
  or an answer too long for the watch (over 95 characters) were left out.
- **bhdone**: the Gate 1 study Q&A at [bhdone.wordpress.com/home/shaar1](https://bhdone.wordpress.com/home/shaar1/),
  turned into four-option questions for this app.
- **Wayground**: other public quizzes: מבחן נשק, קורס מכים מבחן מסכם טירונות, טופוגרפיה וניווט.
- **written**: no public question bank was found for first aid, radio, debriefing or
  topography, so these were written for this app from widely published basics (first-aid
  guidance, radio procedure words, map reading). They are not from the book: check them
  against your course material.

The real *אלוף המקראות* app's question bank is not public, so these are not its questions.

## Build and install

```sh
./gradlew :quiz:assembleDebug          # quiz/build/outputs/apk/debug/quiz-debug.apk
./gradlew :quiz:installDebug           # with the watch connected over ADB (see the main README)
./gradlew :quiz:testDebugUnitTest      # unit tests + screenshots in quiz/build/screenshots/
```
