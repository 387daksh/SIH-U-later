
300 calibration samples (30 english, 270 indic)

rule                                                          coarse accuracy
lid-argmax-always (current legacy metric)                              1.0000
lid-primary(threshold=0.3) + raw-fallback                              1.0000
lid-default(threshold=0.5) + margin-override(margin=0.2)               0.9967
lid-default(threshold=0.5) + margin-override(margin=0.3)               0.9967
lid-default(threshold=0.6) + margin-override(margin=0.3)               0.9867
lid-default(threshold=0.5) + margin-override(margin=0.1)               0.9833
lid-default(threshold=0.6) + margin-override(margin=0.2)               0.9833
lid-default(threshold=0.7) + margin-override(margin=0.3)               0.9767
lid-primary(threshold=0.5) + raw-fallback                              0.9733
lid-default(threshold=0.7) + margin-override(margin=0.2)               0.9733
lid-default(threshold=0.6) + margin-override(margin=0.1)               0.9600
lid-default(threshold=0.7) + margin-override(margin=0.1)               0.9467
lid-primary(threshold=0.6) + raw-fallback                              0.9433
lid-primary(threshold=0.7) + raw-fallback                              0.9267
lid-primary(threshold=0.8) + raw-fallback                              0.9100
lid-primary(threshold=0.9) + raw-fallback                              0.8800
raw-confidence-compare (current fallback, no LID)                      0.3033

Best rule: lid-argmax-always (current legacy metric) (1.0000)
Confusion: {'english->english': 30, 'english->indic': 0, 'indic->english': 0, 'indic->indic': 270}

For reference, current shipped behaviour:
  lid-confident(0.6)+raw-fallback: {'english->english': 30, 'english->indic': 0, 'indic->english': 17, 'indic->indic': 253}
