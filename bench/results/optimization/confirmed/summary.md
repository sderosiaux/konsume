| Distinct records | Scala (s) | Restored Java (s) | Optimized Java (s) | Java time reduction |
|---:|---:|---:|---:|---:|
| 10,000 | 1.7896 | 1.7629 | 1.4471 | 17.91% |
| 100,000 | 4.0497 | 3.2981 | 3.2893 | 0.27% |
| 1,000,000 | 4.7624 | 4.4809 | 4.2419 | 5.33% |

Each time covers 300 million treatments, including iteration and output collection.

| Records | Engine | Means of independent JVMs (s) | Allocated bytes / record |
|---:|---|---|---:|
| 10,000 | optimized | 1.4365, 1.4231, 1.4818 | 1.6552 |
| 10,000 | restored | 1.7362, 1.7263, 1.8262 | 1.6552 |
| 10,000 | scala | 1.8101, 1.7557, 1.8029 | 1.6552 |
| 100,000 | optimized | 3.3590, 3.3794, 3.1295 | 1.3129 |
| 100,000 | restored | 3.1741, 3.4609, 3.2595 | 1.3129 |
| 100,000 | scala | 3.8515, 4.2439, 4.0538 | 1.3129 |
| 1,000,000 | optimized | 4.1192, 4.3925, 4.2139 | 2.0975 |
| 1,000,000 | restored | 4.4412, 4.5696, 4.4318 | 2.0975 |
| 1,000,000 | scala | 4.6384, 4.8716, 4.7773 | 2.0975 |

135 measured invocations; minimum 1.392844 s; maximum 4.929032 s.
