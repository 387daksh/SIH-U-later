(() => {
  const copies = [...document.querySelectorAll('[data-step-copy]')];
  const dots = [...document.querySelectorAll('.progress-dot')];
  const languageGrid = document.querySelector('#language-grid');
  const nextButton = document.querySelector('#next-button');
  const backButton = document.querySelector('.back-button');
  const nameInput = document.querySelector('#name-input');
  const phoneName = document.querySelector('#phone-name');
  const phoneHeading = document.querySelector('#phone-heading');
  const phoneSubtitle = document.querySelector('#phone-subtitle');
  const caption = document.querySelector('#visual-caption-text');
  const phoneStage = document.querySelector('#phone-stage');
  const onboarding = document.querySelector('.onboarding');
  const appView = document.querySelector('#app-view');
  const dashboardName = document.querySelector('#dashboard-name');
  const dashboardLanguage = document.querySelector('#dashboard-language');
  const recordButton = document.querySelector('#record-button');
  const recordCaption = document.querySelector('.record-caption');
  const navItems = [...document.querySelectorAll('.nav-item')];
  const viewPanels = [...document.querySelectorAll('[data-view-panel]')];
  let step = 0;
  let language = 'english';
  let nativeState = null;
  let appShown = false;

  const uiStrings = {
    hindi: { talk:'बात करें', nearby:'आस-पास', helplines:'हेल्पलाइन', history:'इतिहास', more:'और', ready:'बात करने के लिए तैयार', sayWhat:'जो कहना है कहें।', carryIt:'हम पहुँचा देंगे।', preferredLanguage:'आपकी चुनी हुई भाषा है', latestMessage:'नवीनतम संदेश', conversationPulse:'बातचीत की झलक', recentMessages:'हाल के संदेश', conversationHistory:'बातचीत का इतिहास', teamAround:'आपकी टीम', nearbyNow:'अभी आस-पास', connectionTools:'कनेक्शन विकल्प', refreshDiscovery:'खोजें', privateGps:'निजी GPS स्थान', openGpsMap:'लाइव GPS नक्शा खोलें', emergencySafety:'आपातकाल और सुरक्षा', helplineDescription:'आधिकारिक आपातकालीन संपर्क और क्षेत्रीय नियंत्रण कक्ष के नंबर।', voiceLog:'आपकी बातचीत', historyDescription:'हाल के अनुवादित संदेश इस डिवाइस पर उपलब्ध हैं।', controlRoom:'SUTRA नियंत्रण कक्ष', settingsDescription:'अपनी पसंद और ज़रूरी विकल्प यहाँ रखें।' },
    gujarati: { talk:'વાત', nearby:'નજીકમાં', helplines:'હેલ્પલાઇન', history:'ઇતિહાસ', more:'વધુ', ready:'વાત કરવા તૈયાર', sayWhat:'તમારે જે કહેવું છે તે કહો.', carryIt:'અમે પહોંચાડીશું.', preferredLanguage:'તમારી પસંદગીની ભાષા છે', latestMessage:'તાજેતરનો સંદેશ', conversationPulse:'વાતચીતની ઝલક', recentMessages:'તાજેતરના સંદેશા', conversationHistory:'વાતચીતનો ઇતિહાસ', teamAround:'તમારી ટીમ', nearbyNow:'હમણાં નજીકમાં', connectionTools:'કનેક્શન સાધનો', refreshDiscovery:'ફરી શોધો', privateGps:'ખાનગી GPS સ્થાન', openGpsMap:'લાઇવ GPS નકશો ખોલો', emergencySafety:'કટોકટી અને સુરક્ષા', helplineDescription:'સત્તાવાર કટોકટી સંપર્કો અને વિસ્તાર નિયંત્રણ કક્ષના નંબર.', voiceLog:'તમારી વાતચીત', historyDescription:'તાજેતરના અનુવાદિત સંદેશા આ ઉપકરણ પર ઉપલબ્ધ છે.', controlRoom:'SUTRA નિયંત્રણ કક્ષ', settingsDescription:'તમારી પસંદગીઓ અને જરૂરી વિકલ્પો અહીં રાખો.' },
    marathi: { talk:'बोला', nearby:'जवळपास', helplines:'हेल्पलाइन', history:'इतिहास', more:'अधिक', ready:'बोलण्यासाठी तयार', sayWhat:'तुम्हाला काय सांगायचे आहे ते सांगा.', carryIt:'आम्ही पोहोचवू.', preferredLanguage:'तुमची पसंतीची भाषा', latestMessage:'नवीनतम संदेश', conversationPulse:'संवादाची झलक', recentMessages:'अलीकडील संदेश', conversationHistory:'संवादाचा इतिहास', teamAround:'तुमची टीम', nearbyNow:'आत्ता जवळपास', connectionTools:'कनेक्शन साधने', refreshDiscovery:'पुन्हा शोधा', privateGps:'खाजगी GPS स्थान', openGpsMap:'लाइव्ह GPS नकाशा उघडा', emergencySafety:'आपत्कालीन मदत आणि सुरक्षा', helplineDescription:'अधिकृत आपत्कालीन संपर्क आणि क्षेत्र नियंत्रण कक्ष क्रमांक.', voiceLog:'तुमचा संवाद', historyDescription:'अलीकडील भाषांतरित संदेश या डिव्हाइसवर उपलब्ध आहेत.', controlRoom:'SUTRA नियंत्रण कक्ष', settingsDescription:'तुमच्या पसंती आणि आवश्यक पर्याय येथे ठेवा.' },
    bengali: { talk:'কথা', nearby:'কাছাকাছি', helplines:'হেল্পলাইন', history:'ইতিহাস', more:'আরও', ready:'কথা বলার জন্য প্রস্তুত', sayWhat:'আপনি যা বলতে চান বলুন।', carryIt:'আমরা পৌঁছে দেব।', preferredLanguage:'আপনার পছন্দের ভাষা', latestMessage:'সর্বশেষ বার্তা', conversationPulse:'কথোপকথনের ঝলক', recentMessages:'সাম্প্রতিক বার্তা', conversationHistory:'কথোপকথনের ইতিহাস', teamAround:'আপনার দল', nearbyNow:'এখন কাছাকাছি', connectionTools:'সংযোগের সরঞ্জাম', refreshDiscovery:'আবার খুঁজুন', privateGps:'ব্যক্তিগত GPS অবস্থান', openGpsMap:'লাইভ GPS মানচিত্র খুলুন', emergencySafety:'জরুরি ও নিরাপত্তা', helplineDescription:'সরকারি জরুরি যোগাযোগ এবং এলাকার কন্ট্রোল রুম নম্বর।', voiceLog:'আপনার কথোপকথন', historyDescription:'সাম্প্রতিক অনূদিত বার্তা এই ডিভাইসে থাকবে।', controlRoom:'SUTRA কন্ট্রোল রুম', settingsDescription:'আপনার পছন্দ ও প্রয়োজনীয় বিকল্প এখানে রাখুন।' },
    kannada: { talk:'ಮಾತನಾಡಿ', nearby:'ಹತ್ತಿರದಲ್ಲಿ', helplines:'ಸಹಾಯವಾಣಿ', history:'ಇತಿಹಾಸ', more:'ಇನ್ನಷ್ಟು', ready:'ಮಾತನಾಡಲು ಸಿದ್ಧ', sayWhat:'ನೀವು ಹೇಳಬೇಕಾದುದನ್ನು ಹೇಳಿ.', carryIt:'ನಾವು ತಲುಪಿಸುತ್ತೇವೆ.', preferredLanguage:'ನಿಮ್ಮ ಆಯ್ಕೆಯ ಭಾಷೆ', latestMessage:'ಇತ್ತೀಚಿನ ಸಂದೇಶ', conversationPulse:'ಸಂಭಾಷಣೆಯ ನೋಟ', recentMessages:'ಇತ್ತೀಚಿನ ಸಂದೇಶಗಳು', conversationHistory:'ಸಂಭಾಷಣೆ ಇತಿಹಾಸ', teamAround:'ನಿಮ್ಮ ತಂಡ', nearbyNow:'ಈಗ ಹತ್ತಿರದಲ್ಲಿ', connectionTools:'ಸಂಪರ್ಕ ಆಯ್ಕೆಗಳು', refreshDiscovery:'ಮತ್ತೆ ಹುಡುಕಿ', privateGps:'ಖಾಸಗಿ GPS ಸ್ಥಳ', openGpsMap:'ನೇರ GPS ನಕ್ಷೆ ತೆರೆಯಿರಿ', emergencySafety:'ತುರ್ತು ಮತ್ತು ಸುರಕ್ಷತೆ', helplineDescription:'ಅಧಿಕೃತ ತುರ್ತು ಸಂಪರ್ಕಗಳು ಮತ್ತು ಸ್ಥಳೀಯ ನಿಯಂತ್ರಣ ಕೊಠಡಿ ಸಂಖ್ಯೆಗಳು.', voiceLog:'ನಿಮ್ಮ ಸಂಭಾಷಣೆ', historyDescription:'ಇತ್ತೀಚಿನ ಅನುವಾದಿತ ಸಂದೇಶಗಳು ಈ ಸಾಧನದಲ್ಲಿ ಲಭ್ಯವಿವೆ.', controlRoom:'SUTRA ನಿಯಂತ್ರಣ ಕೊಠಡಿ', settingsDescription:'ನಿಮ್ಮ ಆಯ್ಕೆಗಳು ಮತ್ತು ಮುಖ್ಯ ನಿಯಂತ್ರಣಗಳನ್ನು ಇಲ್ಲಿ ಇಡಿ.' },
    malayalam: { talk:'സംസാരിക്കുക', nearby:'സമീപത്ത്', helplines:'ഹെൽപ്‌ലൈൻ', history:'ചരിത്രം', more:'കൂടുതൽ', ready:'സംസാരിക്കാൻ തയ്യാറാണ്', sayWhat:'നിങ്ങൾക്ക് പറയാനുള്ളത് പറയൂ.', carryIt:'ഞങ്ങൾ എത്തിക്കാം.', preferredLanguage:'നിങ്ങളുടെ തിരഞ്ഞെടുത്ത ഭാഷ', latestMessage:'ഏറ്റവും പുതിയ സന്ദേശം', conversationPulse:'സംഭാഷണ സംഗ്രഹം', recentMessages:'സമീപകാല സന്ദേശങ്ങൾ', conversationHistory:'സംഭാഷണ ചരിത്രം', teamAround:'നിങ്ങളുടെ സംഘം', nearbyNow:'ഇപ്പോൾ സമീപത്ത്', connectionTools:'കണക്ഷൻ ഉപകരണങ്ങൾ', refreshDiscovery:'വീണ്ടും തിരയുക', privateGps:'സ്വകാര്യ GPS സ്ഥാനം', openGpsMap:'തത്സമയ GPS മാപ്പ് തുറക്കുക', emergencySafety:'അടിയന്തരവും സുരക്ഷയും', helplineDescription:'ഔദ്യോഗിക അടിയന്തര ബന്ധപ്പെടൽ നമ്പറുകളും പ്രദേശ നിയന്ത്രണ മുറി നമ്പറുകളും.', voiceLog:'നിങ്ങളുടെ സംഭാഷണം', historyDescription:'സമീപകാല വിവർത്തന സന്ദേശങ്ങൾ ഈ ഉപകരണത്തിൽ ലഭ്യമാണ്.', controlRoom:'SUTRA നിയന്ത്രണ കേന്ദ്രം', settingsDescription:'നിങ്ങളുടെ ഇഷ്ടങ്ങളും പ്രധാന നിയന്ത്രണങ്ങളും ഇവിടെ.' },
    tamil: { talk:'பேசு', nearby:'அருகில்', helplines:'உதவி எண்கள்', history:'வரலாறு', more:'மேலும்', ready:'பேசத் தயார்', sayWhat:'நீங்கள் சொல்ல நினைப்பதைச் சொல்லுங்கள்.', carryIt:'நாங்கள் கொண்டு சேர்ப்போம்.', preferredLanguage:'நீங்கள் தேர்ந்தெடுத்த மொழி', latestMessage:'சமீபத்திய செய்தி', conversationPulse:'உரையாடல் சுருக்கம்', recentMessages:'சமீபத்திய செய்திகள்', conversationHistory:'உரையாடல் வரலாறு', teamAround:'உங்கள் குழு', nearbyNow:'இப்போது அருகில்', connectionTools:'இணைப்பு கருவிகள்', refreshDiscovery:'மீண்டும் தேடு', privateGps:'தனிப்பட்ட GPS இடம்', openGpsMap:'நேரடி GPS வரைபடத்தைத் திற', emergencySafety:'அவசரநிலை மற்றும் பாதுகாப்பு', helplineDescription:'அதிகாரப்பூர்வ அவசர தொடர்புகள் மற்றும் பகுதி கட்டுப்பாட்டு அறை எண்கள்.', voiceLog:'உங்கள் உரையாடல்', historyDescription:'சமீபத்திய மொழிபெயர்க்கப்பட்ட செய்திகள் இந்தச் சாதனத்தில் கிடைக்கும்.', controlRoom:'SUTRA கட்டுப்பாட்டு அறை', settingsDescription:'உங்கள் விருப்பங்களும் முக்கிய கட்டுப்பாடுகளும் இங்கே.' },
    telugu: { talk:'మాట్లాడండి', nearby:'దగ్గరలో', helplines:'హెల్ప్‌లైన్', history:'చరిత్ర', more:'మరిన్ని', ready:'మాట్లాడటానికి సిద్ధం', sayWhat:'మీరు చెప్పాలనుకున్నది చెప్పండి.', carryIt:'మేము చేరవేస్తాం.', preferredLanguage:'మీరు ఎంచుకున్న భాష', latestMessage:'తాజా సందేశం', conversationPulse:'సంభాషణ సారాంశం', recentMessages:'ఇటీవలి సందేశాలు', conversationHistory:'సంభాషణ చరిత్ర', teamAround:'మీ బృందం', nearbyNow:'ఇప్పుడు దగ్గరలో', connectionTools:'కనెక్షన్ సాధనాలు', refreshDiscovery:'మళ్లీ వెతకండి', privateGps:'ప్రైవేట్ GPS స్థానం', openGpsMap:'లైవ్ GPS మ్యాప్ తెరవండి', emergencySafety:'అత్యవసరం మరియు భద్రత', helplineDescription:'అధికారిక అత్యవసర సంప్రదింపులు మరియు ప్రాంత నియంత్రణ గది నంబర్లు.', voiceLog:'మీ సంభాషణ', historyDescription:'ఇటీవలి అనువాద సందేశాలు ఈ పరికరంలో అందుబాటులో ఉంటాయి.', controlRoom:'SUTRA నియంత్రణ గది', settingsDescription:'మీ అభిరుచులు మరియు ముఖ్య నియంత్రణలు ఇక్కడ.' },
    odia: { talk:'କଥା ହୁଅନ୍ତୁ', nearby:'ପାଖରେ', helplines:'ସହାୟତା ନମ୍ବର', history:'ଇତିହାସ', more:'ଅଧିକ', ready:'କଥା ହେବାକୁ ପ୍ରସ୍ତୁତ', sayWhat:'ଆପଣ ଯାହା କହିବାକୁ ଚାହାନ୍ତି କୁହନ୍ତୁ।', carryIt:'ଆମେ ପହଞ୍ଚାଇବୁ।', preferredLanguage:'ଆପଣଙ୍କ ପସନ୍ଦର ଭାଷା', latestMessage:'ସଦ୍ୟତମ ବାର୍ତ୍ତା', conversationPulse:'କଥାବାର୍ତ୍ତାର ଝଲକ', recentMessages:'ସାମ୍ପ୍ରତିକ ବାର୍ତ୍ତା', conversationHistory:'କଥାବାର୍ତ୍ତା ଇତିହାସ', teamAround:'ଆପଣଙ୍କ ଦଳ', nearbyNow:'ବର୍ତ୍ତମାନ ପାଖରେ', connectionTools:'ସଂଯୋଗ ଉପକରଣ', refreshDiscovery:'ପୁଣି ଖୋଜନ୍ତୁ', privateGps:'ବ୍ୟକ୍ତିଗତ GPS ସ୍ଥାନ', openGpsMap:'ଲାଇଭ୍ GPS ମାନଚିତ୍ର ଖୋଲନ୍ତୁ', emergencySafety:'ଜରୁରୀକାଳୀନ ଓ ସୁରକ୍ଷା', helplineDescription:'ସରକାରୀ ଜରୁରୀ ସମ୍ପର୍କ ଏବଂ ଅଞ୍ଚଳ ନିୟନ୍ତ୍ରଣ କକ୍ଷ ନମ୍ବର।', voiceLog:'ଆପଣଙ୍କ କଥାବାର୍ତ୍ତା', historyDescription:'ସାମ୍ପ୍ରତିକ ଅନୁବାଦିତ ବାର୍ତ୍ତା ଏହି ଡିଭାଇସରେ ଉପଲବ୍ଧ।', controlRoom:'SUTRA ନିୟନ୍ତ୍ରଣ କକ୍ଷ', settingsDescription:'ଆପଣଙ୍କ ପସନ୍ଦ ଓ ମୁଖ୍ୟ ବିକଳ୍ପ ଏଠାରେ।' }
  };
  const uiExtra = {
    hindi: { howToUse:'SUTRA का उपयोग कैसे करें', languageSetting:'भाषा', directTools:'सीधे कनेक्शन के विकल्प', gpsSetting:'लाइव GPS नक्शा', emergencyContacts:'आपातकालीन हेल्पलाइन', replayWelcome:'परिचय फिर से देखें', chooseLanguage:'भाषा चुनें', gotIt:'समझ गया', handsFreeAction:'हैंड्स-फ़्री', stopHandsFree:'हैंड्स-फ़्री बंद करें', emergencyAction:'आपातकालीन अलर्ट', emergencyActive:'अलर्ट चालू · रद्द करने के लिए टैप करें', call112:'112 पर कॉल करें', shareLocation:'अपना स्थान साझा करें', stopSharing:'स्थान साझा करना बंद करें' },
    gujarati: { howToUse:'SUTRA નો ઉપયોગ કેવી રીતે કરવો', languageSetting:'ભાષા', directTools:'સીધા કનેક્શન સાધનો', gpsSetting:'લાઇવ GPS નકશો', emergencyContacts:'કટોકટી હેલ્પલાઇન', replayWelcome:'પરિચય ફરી જુઓ', chooseLanguage:'ભાષા પસંદ કરો', gotIt:'સમજાયું', handsFreeAction:'હેન્ડ્સ-ફ્રી', stopHandsFree:'હેન્ડ્સ-ફ્રી બંધ કરો', emergencyAction:'કટોકટી ચેતવણી', emergencyActive:'ચેતવણી ચાલુ · રદ કરવા ટેપ કરો', call112:'112 પર કૉલ કરો', shareLocation:'મારું સ્થાન શેર કરો', stopSharing:'સ્થાન શેર કરવાનું બંધ કરો' },
    marathi: { howToUse:'SUTRA कसे वापरावे', languageSetting:'भाषा', directTools:'थेट कनेक्शन साधने', gpsSetting:'लाइव्ह GPS नकाशा', emergencyContacts:'आपत्कालीन हेल्पलाइन', replayWelcome:'परिचय पुन्हा पहा', chooseLanguage:'भाषा निवडा', gotIt:'समजले', handsFreeAction:'हँड्स-फ्री', stopHandsFree:'हँड्स-फ्री थांबवा', emergencyAction:'आपत्कालीन सूचना', emergencyActive:'सूचना सुरू · रद्द करण्यासाठी टॅप करा', call112:'112 वर कॉल करा', shareLocation:'माझे स्थान शेअर करा', stopSharing:'स्थान शेअर करणे थांबवा' },
    bengali: { howToUse:'SUTRA কীভাবে ব্যবহার করবেন', languageSetting:'ভাষা', directTools:'সরাসরি সংযোগের সরঞ্জাম', gpsSetting:'লাইভ GPS মানচিত্র', emergencyContacts:'জরুরি হেল্পলাইন', replayWelcome:'পরিচিতি আবার দেখুন', chooseLanguage:'ভাষা বেছে নিন', gotIt:'বুঝেছি', handsFreeAction:'হ্যান্ডস-ফ্রি', stopHandsFree:'হ্যান্ডস-ফ্রি বন্ধ করুন', emergencyAction:'জরুরি সতর্কতা', emergencyActive:'সতর্কতা চালু · বাতিল করতে ট্যাপ করুন', call112:'112-এ কল করুন', shareLocation:'আমার অবস্থান শেয়ার করুন', stopSharing:'অবস্থান শেয়ার বন্ধ করুন' },
    kannada: { howToUse:'SUTRA ಬಳಸುವುದು ಹೇಗೆ', languageSetting:'ಭಾಷೆ', directTools:'ನೇರ ಸಂಪರ್ಕ ಸಾಧನಗಳು', gpsSetting:'ನೇರ GPS ನಕ್ಷೆ', emergencyContacts:'ತುರ್ತು ಸಹಾಯವಾಣಿ', replayWelcome:'ಪರಿಚಯವನ್ನು ಮತ್ತೆ ನೋಡಿ', chooseLanguage:'ಭಾಷೆಯನ್ನು ಆಯ್ಕೆಮಾಡಿ', gotIt:'ಅರ್ಥವಾಯಿತು', handsFreeAction:'ಹ್ಯಾಂಡ್ಸ್-ಫ್ರೀ', stopHandsFree:'ಹ್ಯಾಂಡ್ಸ್-ಫ್ರೀ ನಿಲ್ಲಿಸಿ', emergencyAction:'ತುರ್ತು ಎಚ್ಚರಿಕೆ', emergencyActive:'ಎಚ್ಚರಿಕೆ ಸಕ್ರಿಯ · ರದ್ದುಮಾಡಲು ಟ್ಯಾಪ್ ಮಾಡಿ', call112:'112ಕ್ಕೆ ಕರೆಮಾಡಿ', shareLocation:'ನನ್ನ ಸ್ಥಳ ಹಂಚಿಕೊಳ್ಳಿ', stopSharing:'ಸ್ಥಳ ಹಂಚಿಕೆ ನಿಲ್ಲಿಸಿ' },
    malayalam: { howToUse:'SUTRA എങ്ങനെ ഉപയോഗിക്കാം', languageSetting:'ഭാഷ', directTools:'നേരിട്ടുള്ള കണക്ഷൻ ഉപകരണങ്ങൾ', gpsSetting:'തത്സമയ GPS മാപ്പ്', emergencyContacts:'അടിയന്തര ഹെൽപ്‌ലൈൻ', replayWelcome:'ആമുഖം വീണ്ടും കാണുക', chooseLanguage:'ഭാഷ തിരഞ്ഞെടുക്കുക', gotIt:'മനസ്സിലായി', handsFreeAction:'ഹാൻഡ്‌സ്-ഫ്രീ', stopHandsFree:'ഹാൻഡ്‌സ്-ഫ്രീ നിർത്തുക', emergencyAction:'അടിയന്തര മുന്നറിയിപ്പ്', emergencyActive:'മുന്നറിയിപ്പ് ഓണാണ് · റദ്ദാക്കാൻ തൊടുക', call112:'112-ലേക്ക് വിളിക്കുക', shareLocation:'എന്റെ സ്ഥലം പങ്കിടുക', stopSharing:'സ്ഥലം പങ്കിടുന്നത് നിർത്തുക' },
    tamil: { howToUse:'SUTRA பயன்படுத்துவது எப்படி', languageSetting:'மொழி', directTools:'நேரடி இணைப்பு கருவிகள்', gpsSetting:'நேரடி GPS வரைபடம்', emergencyContacts:'அவசர உதவி எண்கள்', replayWelcome:'அறிமுகத்தை மீண்டும் காண்க', chooseLanguage:'மொழியைத் தேர்ந்தெடுக்கவும்', gotIt:'புரிந்தது', handsFreeAction:'ஹேண்ட்ஸ்-ஃப்ரீ', stopHandsFree:'ஹேண்ட்ஸ்-ஃப்ரீயை நிறுத்து', emergencyAction:'அவசர எச்சரிக்கை', emergencyActive:'எச்சரிக்கை இயங்குகிறது · ரத்துசெய்யத் தட்டவும்', call112:'112-ஐ அழைக்கவும்', shareLocation:'எனது இருப்பிடத்தைப் பகிர்', stopSharing:'இருப்பிடப் பகிர்வை நிறுத்து' },
    telugu: { howToUse:'SUTRA ఎలా ఉపయోగించాలి', languageSetting:'భాష', directTools:'నేర కనెక్షన్ సాధనాలు', gpsSetting:'లైవ్ GPS మ్యాప్', emergencyContacts:'అత్యవసర హెల్ప్‌లైన్‌లు', replayWelcome:'పరిచయాన్ని మళ్లీ చూడండి', chooseLanguage:'భాషను ఎంచుకోండి', gotIt:'అర్థమైంది', handsFreeAction:'హ్యాండ్స్-ఫ్రీ', stopHandsFree:'హ్యాండ్స్-ఫ్రీ ఆపండి', emergencyAction:'అత్యవసర హెచ్చరిక', emergencyActive:'హెచ్చరిక ఆన్‌లో ఉంది · రద్దు చేయడానికి నొక్కండి', call112:'112కు కాల్ చేయండి', shareLocation:'నా స్థానాన్ని పంచుకోండి', stopSharing:'స్థాన భాగస్వామ్యాన్ని ఆపండి' },
    odia: { howToUse:'SUTRA କିପରି ବ୍ୟବହାର କରିବେ', languageSetting:'ଭାଷା', directTools:'ସିଧାସଳଖ ସଂଯୋଗ ଉପକରଣ', gpsSetting:'ଲାଇଭ୍ GPS ମାନଚିତ୍ର', emergencyContacts:'ଜରୁରୀ ସହାୟତା ନମ୍ବର', replayWelcome:'ପରିଚୟ ପୁଣି ଦେଖନ୍ତୁ', chooseLanguage:'ଭାଷା ବାଛନ୍ତୁ', gotIt:'ବୁଝିଲି', handsFreeAction:'ହ୍ୟାଣ୍ଡ୍ସ-ଫ୍ରି', stopHandsFree:'ହ୍ୟାଣ୍ଡ୍ସ-ଫ୍ରି ବନ୍ଦ କରନ୍ତୁ', emergencyAction:'ଜରୁରୀ ସତର୍କତା', emergencyActive:'ସତର୍କତା ଚାଲୁ · ବାତିଲ୍ ପାଇଁ ଟାପ୍ କରନ୍ତୁ', call112:'112କୁ କଲ୍ କରନ୍ତୁ', shareLocation:'ମୋ ଅବସ୍ଥାନ ସେୟାର୍ କରନ୍ତୁ', stopSharing:'ଅବସ୍ଥାନ ସେୟାର୍ ବନ୍ଦ କରନ୍ତୁ' }
  };
  Object.entries(uiExtra).forEach(([key, values]) => Object.assign(uiStrings[key], values));
  const nearbyCopy = {
    hindi: ['SUTRA फोन कनेक्ट होने पर टीम के सदस्य यहाँ दिखेंगे।', 'कोई टीम सदस्य अभी कनेक्ट नहीं है।', 'पास में कनेक्ट है', 'आखिरी बार {seconds} सेकंड पहले देखा गया', 'दूरी उपलब्ध नहीं'],
    gujarati: ['SUTRA ફોન જોડાય ત્યારે ટીમના સભ્યો અહીં દેખાશે.', 'હજુ કોઈ ટીમ સભ્ય જોડાયેલ નથી.', 'નજીકમાં જોડાયેલ', 'છેલ્લે {seconds} સેકન્ડ પહેલાં દેખાયા', 'અંતર ઉપલબ્ધ નથી'],
    marathi: ['SUTRA फोन जोडले गेल्यावर टीमचे सदस्य येथे दिसतील.', 'अजून कोणी टीम सदस्य जोडलेले नाहीत.', 'जवळपास जोडलेले', 'शेवटचे {seconds} सेकंदांपूर्वी दिसले', 'अंतर उपलब्ध नाही'],
    bengali: ['SUTRA ফোন সংযুক্ত হলে দলের সদস্যরা এখানে দেখা যাবে।', 'এখনও কোনো দলের সদস্য সংযুক্ত নেই।', 'কাছাকাছি সংযুক্ত', 'শেষ দেখা {seconds} সেকেন্ড আগে', 'দূরত্ব উপলব্ধ নয়'],
    kannada: ['SUTRA ಫೋನ್‌ಗಳು ಸಂಪರ್ಕಗೊಂಡಾಗ ತಂಡದ ಸದಸ್ಯರು ಇಲ್ಲಿ ಕಾಣಿಸುತ್ತಾರೆ.', 'ಇನ್ನೂ ತಂಡದ ಸದಸ್ಯರು ಸಂಪರ್ಕಗೊಂಡಿಲ್ಲ.', 'ಹತ್ತಿರದಲ್ಲಿ ಸಂಪರ್ಕಗೊಂಡಿದೆ', 'ಕೊನೆಯದಾಗಿ {seconds} ಸೆಕೆಂಡುಗಳ ಹಿಂದೆ ಕಂಡುಬಂದಿದೆ', 'ದೂರ ಲಭ್ಯವಿಲ್ಲ'],
    malayalam: ['SUTRA ഫോണുകൾ ബന്ധിപ്പിക്കുമ്പോൾ ടീമംഗങ്ങൾ ഇവിടെ കാണിക്കും.', 'ടീമംഗങ്ങളാരും ഇതുവരെ ബന്ധിപ്പിച്ചിട്ടില്ല.', 'സമീപത്ത് ബന്ധിപ്പിച്ചു', 'അവസാനം കണ്ടത് {seconds} സെക്കൻഡ് മുമ്പ്', 'ദൂരം ലഭ്യമല്ല'],
    tamil: ['SUTRA தொலைபேசிகள் இணைந்ததும் குழு உறுப்பினர்கள் இங்கே தோன்றுவார்கள்.', 'குழு உறுப்பினர்கள் இன்னும் இணைக்கப்படவில்லை.', 'அருகில் இணைக்கப்பட்டுள்ளது', 'கடைசியாக {seconds} வினாடிகளுக்கு முன் காணப்பட்டது', 'தூரம் கிடைக்கவில்லை'],
    telugu: ['SUTRA ఫోన్‌లు కనెక్ట్ అయినప్పుడు బృంద సభ్యులు ఇక్కడ కనిపిస్తారు.', 'ఇంకా బృంద సభ్యులు కనెక్ట్ కాలేదు.', 'దగ్గరలో కనెక్ట్ అయింది', 'చివరిగా {seconds} సెకన్ల క్రితం కనిపించారు', 'దూరం అందుబాటులో లేదు'],
    odia: ['SUTRA ଫୋନ୍ ସଂଯୋଗ ହେଲେ ଦଳର ସଦସ୍ୟମାନେ ଏଠାରେ ଦେଖାଯିବେ।', 'ଏପର୍ଯ୍ୟନ୍ତ କୌଣସି ଦଳ ସଦସ୍ୟ ସଂଯୋଗ ହୋଇନାହାନ୍ତି।', 'ନିକଟରେ ସଂଯୋଗ ହୋଇଛି', 'ଶେଷଥର {seconds} ସେକେଣ୍ଡ ପୂର୍ବରୁ ଦେଖାଗଲା', 'ଦୂରତା ଉପଲବ୍ଧ ନାହିଁ']
  };
  Object.entries(nearbyCopy).forEach(([key, [nearbyDescription, noPeers, connected, lastSeen, noDistance]]) => {
    Object.assign(uiStrings[key], { nearbyDescription, noPeers, connected, lastSeen, noDistance });
  });
  Object.assign(uiStrings.hindi, {
    quickWalkthrough:'छोटा परिचय', holdSpeakRelease:'दबाएँ · बोलें · छोड़ें',
    translatedForTeam:'आपका संदेश जुड़े हुए साथियों की भाषा में पहुँचता है।',
    holdRelease:'बड़े बटन को दबाकर रखें। भेजने के लिए छोड़ दें।',
    handsFree:'हैंड्स-फ़्री', continuousListening:'लगातार सुनना शुरू करें और पूरा होने पर रोकें।',
    urgentHelp:'तुरंत मदद', emergencyFlags:'आपातकालीन अलर्ट आपके अगले टीम संदेश को चिह्नित करता है। आपातकालीन सेवा के लिए 112 पर कॉल करें।',
    locationChoice:'स्थान तभी साझा करें जब आप चाहें। हेल्पलाइन नंबर ऑफ़लाइन काम करते हैं।',
    yourPreference:'आपकी पसंद', languageSettingDetail:'अनुवाद की भाषा बदलें',
    directToolsDetail:'Wi-Fi Direct, Bluetooth और मैन्युअल IP', gpsSettingDetail:'आपका स्थान, सटीकता और साथियों के स्थान',
    emergencyContactsDetail:'राष्ट्रीय नंबर और क्षेत्रीय नियंत्रण कक्ष', helpDetail:'बातचीत, आपातकालीन अलर्ट और नक्शे',
    replayWelcomeDetail:'तीन चरणों वाला परिचय फिर देखें', areaHelp:'क्षेत्र के अनुसार आपातकालीन सहायता',
    findArea:'अपना क्षेत्र खोजें', useGps:'GPS से मेरा क्षेत्र खोजें', notNow:'अभी नहीं'
  });

  const applyUiLanguage = key => {
    const strings = uiStrings[key];
    document.querySelectorAll('[data-ui]').forEach(node => {
      node.dataset.defaultUi ??= node.textContent;
      const value = strings?.[node.dataset.ui];
      node.textContent = value || node.dataset.defaultUi;
    });
    const share = document.querySelector('#location-share-button');
    if (share) share.textContent = nativeState?.locationSharing ? (strings?.stopSharing || 'Stop sharing location') : (strings?.shareLocation || 'Share my location');
    const handsfree = document.querySelector('#handsfree-button');
    if (handsfree) handsfree.textContent = nativeState?.handsFree ? (strings?.stopHandsFree || 'Stop hands-free') : (strings?.handsFreeAction || 'Hands-free');
    const emergency = document.querySelector('#emergency-button');
    if (emergency) emergency.textContent = nativeState?.alertMode ? (strings?.emergencyActive || 'Emergency mode on · tap to cancel') : (strings?.emergencyAction || 'Emergency alert');
  };

  const languageLabel = key => nativeState?.languages?.find(item => item.key === key)?.englishName || key;
  const renderLanguageOptions = languages => {
    languageGrid.replaceChildren();
    (languages || []).forEach(item => {
      const card = document.createElement('button');
      card.type = 'button'; card.className = 'language-card'; card.setAttribute('role', 'radio');
      card.dataset.language = item.key; card.setAttribute('aria-checked', 'false');
      const native = document.createElement('span'); native.className = 'language-native'; native.textContent = item.nativeName;
      const detail = document.createElement('span'); detail.className = 'language-detail'; detail.textContent = item.englishName;
      const check = document.createElement('span'); check.className = 'language-check'; check.textContent = '✓';
      card.append(native, detail, check); languageGrid.append(card);
    });
    setLanguage(nativeState?.languageKey || 'english');
  };

  const setLanguage = key => {
    language = key;
    document.querySelectorAll('.language-card').forEach(card => {
      const selected = card.dataset.language === key;
      card.classList.toggle('is-selected', selected);
      card.setAttribute('aria-checked', String(selected));
    });
    render();
  };

  const render = () => {
    copies.forEach((copy, index) => copy.classList.toggle('is-hidden', index !== step));
    dots.forEach((dot, index) => dot.classList.toggle('is-active', index === step));
    backButton.disabled = step === 0;
    nextButton.innerHTML = step === 2 ? 'Enter SUTRA <span aria-hidden="true">→</span>' : 'Continue <span aria-hidden="true">→</span>';
    caption.textContent = step === 0 ? 'Your voice, your way' : step === 1 ? `${languageLabel(language)} feels at home` : 'Closer, even offline';
    if (step === 1) phoneSubtitle.textContent = `${languageLabel(language)} is ready for nearby conversations.`;
    if (step === 2) phoneSubtitle.textContent = 'Speak naturally. SUTRA keeps your team in sync.';
  };

  nextButton.addEventListener('click', () => {
    if (step === 0 && nameInput.value.trim()) phoneName.textContent = `Hi, ${nameInput.value.trim().split(/\s+/)[0]}`;
    if (step < 2) { step += 1; render(); }
    else {
      const firstName = nameInput.value.trim().split(/\s+/)[0];
      if (window.SutraAndroid) {
        window.SutraAndroid.finishOnboarding(nameInput.value, language);
      } else {
        dashboardName.textContent = firstName || 'there';
        dashboardLanguage.textContent = languageLabel(language);
        onboarding.classList.add('is-hidden');
        appView.classList.remove('is-hidden');
        document.body.classList.add('app-active');
      }
    }
  });

  backButton.addEventListener('click', () => { if (step > 0) { step -= 1; render(); } });

  languageGrid.addEventListener('click', event => {
    const card = event.target.closest('.language-card');
    if (card) setLanguage(card.dataset.language);
  });

  const startRecord = event => {
    event.preventDefault();
    if (recordButton.classList.contains('is-listening')) return;
    recordButton.classList.add('is-listening');
    recordButton.setAttribute('aria-label', 'Recording. Release to send');
    recordCaption.textContent = 'Listening…';
    window.SutraAndroid?.beginTalk();
  };
  const stopRecord = () => {
    if (!recordButton.classList.contains('is-listening')) return;
    recordButton.classList.remove('is-listening');
    recordButton.setAttribute('aria-label', 'Hold to talk');
    recordCaption.textContent = 'Hold to talk';
    window.SutraAndroid?.endTalk();
  };
  recordButton.addEventListener('pointerdown', startRecord);
  recordButton.addEventListener('pointerup', stopRecord);
  recordButton.addEventListener('pointercancel', stopRecord);
  recordButton.addEventListener('lostpointercapture', stopRecord);
  recordButton.addEventListener('click', event => {
    if (event.detail === 0 && window.SutraAndroid) {
      window.SutraAndroid.beginTalk();
      setTimeout(() => window.SutraAndroid.endTalk(), 900);
    }
  });

  navItems.forEach(item => item.addEventListener('click', () => {
    navItems.forEach(navItem => navItem.classList.remove('is-active'));
    item.classList.add('is-active');
    viewPanels.forEach(panel => panel.classList.toggle('is-hidden', panel.dataset.viewPanel !== item.dataset.view));
  }));

  const addPeerRows = (container, peers) => {
    const nearbyStrings = uiStrings[nativeState?.languageKey] || {};
    container.replaceChildren();
    if (!peers.length) {
      const empty = document.createElement('div'); empty.className = 'empty-state';
      empty.textContent = nearbyStrings.noPeers || 'No connected teammates yet.'; container.append(empty); return;
    }
    peers.forEach((peer, index) => {
      const row = document.createElement('div'); row.className = 'teammate-row';
      const avatar = document.createElement('span'); avatar.className = `person-avatar ${['avatar-mint', 'avatar-coral', 'avatar-gold'][index % 3]}`;
      avatar.textContent = (peer.name || 'S').slice(0, 1).toUpperCase();
      const copy = document.createElement('span');
      const strong = document.createElement('strong'); strong.textContent = peer.name || 'SUTRA phone';
      const small = document.createElement('small'); small.textContent = peer.fresh ? (nearbyStrings.connected || 'Connected nearby') : (nearbyStrings.lastSeen || 'Last seen {seconds} seconds ago').replace('{seconds}', peer.ageSeconds);
      copy.append(strong, small);
      const distance = document.createElement('span'); distance.className = 'teammate-distance';
      distance.textContent = peer.distance || nearbyStrings.noDistance || 'Distance unavailable';
      row.append(avatar, copy, distance); container.append(row);
    });
  };

  const renderMessages = messages => {
    const latest = document.querySelector('#latest-message');
    const history = document.querySelector('#history-list');
    const pulse = document.querySelector('#talk-pulse');
    latest.replaceChildren(); history.replaceChildren(); pulse?.replaceChildren();
    if (!messages.length) {
      const empty = document.createElement('div'); empty.className = 'empty-state';
      empty.textContent = 'Your sent and received messages will appear here.';
      latest.append(empty);
      if (pulse) { const pulseEmpty = empty.cloneNode(true); pulseEmpty.textContent = 'Recent messages appear here.'; pulse.append(pulseEmpty); }
      const historyEmpty = document.createElement('div'); historyEmpty.className = 'empty-state';
      historyEmpty.textContent = 'No messages yet.'; history.append(historyEmpty); return;
    }
    const pulseRow = message => {
      const row = document.createElement('article'); row.className = `pulse-message${message.alert ? ' is-emergency' : ''}`;
      const heading = document.createElement('div'); heading.className = 'pulse-message-heading';
      const who = document.createElement('strong'); who.textContent = message.alert ? 'EMERGENCY' : message.outgoing ? 'You' : 'Nearby teammate';
      const lang = document.createElement('small'); lang.textContent = message.language || '';
      heading.append(who, lang);
      const text = document.createElement('p'); text.textContent = message.text || '';
      const status = document.createElement('small'); status.className = 'pulse-message-status'; status.textContent = message.status || '';
      const actions = document.createElement('div'); actions.className = 'pulse-message-actions';
      const replay = document.createElement('button'); replay.type = 'button'; replay.className = 'message-action'; replay.textContent = 'Replay';
      replay.setAttribute('aria-label', `Replay message: ${(message.text || '').slice(0, 80)}`);
      replay.addEventListener('click', () => window.SutraAndroid?.replayMessage(message.text, message.languageKey));
      const copy = document.createElement('button'); copy.type = 'button'; copy.className = 'message-action'; copy.textContent = 'Copy';
      copy.setAttribute('aria-label', `Copy message: ${(message.text || '').slice(0, 80)}`);
      copy.addEventListener('click', () => window.SutraAndroid?.copyMessage(message.text));
      actions.append(replay, copy);
      row.append(heading, text, status, actions);
      return row;
    };
    const last = messages[messages.length - 1];
    latest.append(pulseRow(last));
    messages.slice(-6).reverse().forEach(message => pulse?.append(pulseRow(message)));
    [...messages].reverse().forEach((message, index) => {
      const item = document.createElement('div'); item.className = `history-item${message.alert ? ' is-emergency' : ''}`;
      const date = document.createElement('span'); date.className = 'history-date'; date.textContent = index === 0 ? 'LATEST MESSAGE' : 'MESSAGE';
      const title = document.createElement('strong'); title.textContent = `${message.outgoing ? 'Sent' : 'Received'}${message.alert ? ' · Emergency' : ''}`;
      const preview = document.createElement('span'); preview.className = 'history-preview'; preview.textContent = message.text || '';
      const tag = document.createElement('span'); tag.className = 'history-tag'; tag.textContent = message.language || '';
      item.append(date, title, preview, tag); history.append(item);
      const actions = document.createElement('div'); actions.className = 'message-actions';
      const replay = document.createElement('button'); replay.type = 'button'; replay.className = 'message-action'; replay.textContent = 'Replay';
      replay.addEventListener('click', () => window.SutraAndroid?.replayMessage(message.text, message.languageKey));
      const copy = document.createElement('button'); copy.type = 'button'; copy.className = 'message-action'; copy.textContent = 'Copy';
      copy.addEventListener('click', () => window.SutraAndroid?.copyMessage(message.text));
      actions.append(replay, copy); item.append(actions);
    });
  };

  const renderMap = state => {
    const list = document.querySelector('#helpline-list');
    if (!list) return;
    const hasOrigin = Number.isFinite(state.latitude) && Number.isFinite(state.longitude);
    document.querySelector('#nearby-map-label').textContent = hasOrigin
      ? `${state.latitude.toFixed(5)}, ${state.longitude.toFixed(5)}`
      : 'Waiting for location permission';
    document.querySelector('#location-status').textContent = hasOrigin
      ? `±${Math.round(state.locationAccuracy || 0)} m accuracy · ${state.locationSharing ? 'sharing with nearby SUTRA phones' : 'coordinates private on this phone'}`
      : 'Your coordinates stay on this phone until you enable sharing.';
    const share = document.querySelector('#location-share-button');
    const translatedUi = uiStrings[state.languageKey];
    share.textContent = state.locationSharing
      ? (translatedUi?.stopSharing || 'Stop sharing location')
      : (translatedUi?.shareLocation || 'Share my location');
    share.classList.toggle('is-active', !!state.locationSharing);
    window.sutraLocationSharing = !!state.locationSharing;
    const areaName = [state.areaDistrict, state.areaState].filter(Boolean).join(', ');
    document.querySelector('#area-name').textContent = areaName || (
      state.areaLookupStatus === 'resolving' ? 'Finding your state and district…' :
      state.areaLookupStatus === 'locating' ? 'Waiting for a GPS fix…' : 'Find your state and district'
    );
    document.querySelector('#area-detail').textContent = areaName
      ? 'Emergency codes shown for this area · GPS lookup stays private from teammates.'
      : state.areaLookupStatus === 'error'
        ? 'Could not resolve your area. 112 works nationwide; 1070 and 1077 are listed below.'
        : 'Location lookup is separate from teammate sharing.';
    const areaCalls = document.querySelector('#area-call-list');
    areaCalls.replaceChildren();
    (state.areaHelplines || []).forEach(contact => {
      const row = document.createElement('div'); row.className = 'area-call-row';
      const label = document.createElement('span');
      const name = document.createElement('strong'); name.textContent = contact.name;
      const scope = document.createElement('small'); scope.textContent = contact.area || '';
      label.append(name, scope);
      const call = document.createElement('button'); call.type = 'button'; call.className = 'helpline-call';
      call.textContent = `Call ${contact.number}`;
      call.addEventListener('click', () => window.SutraAndroid?.dialHelpline(contact.number));
      row.append(label, call); areaCalls.append(row);
    });
    list.replaceChildren();
    const groups = new Map();
    (state.helplines || []).forEach(contact => {
      if (!groups.has(contact.category)) groups.set(contact.category, []);
      groups.get(contact.category).push(contact);
    });
    groups.forEach((contacts, category) => {
      const heading = document.createElement('h4'); heading.className = 'helpline-category'; heading.textContent = category;
      list.append(heading);
      contacts.forEach(contact => {
        const row = document.createElement('div'); row.className = 'helpline-row';
        const info = document.createElement('div');
        const title = document.createElement('strong'); title.textContent = contact.name;
        const detail = document.createElement('small');
        detail.textContent = `${contact.number}${contact.scope && contact.scope !== 'India-wide' ? ` · ${contact.scope}` : ''}`;
        info.append(title, detail);
        const call = document.createElement('button'); call.type = 'button'; call.className = 'helpline-call'; call.textContent = 'Call';
        call.setAttribute('aria-label', `Call ${contact.name}, ${contact.number}`);
        call.addEventListener('click', () => window.SutraAndroid?.dialHelpline(contact.number));
        row.append(info, call); list.append(row);
      });
    });
    document.querySelector('#directory-count').textContent = `${(state.helplines || []).length} contacts · offline`;
    document.querySelector('#directory-source').textContent = `National Portal of India · checked ${state.helplineChecked || 'date unavailable'} · 1070/1077 codes assigned by MHA`;
  };

  window.sutraRenderState = state => {
    if (!state) return;
    nativeState = state;
    window.sutraNativeState = state;
    applyUiLanguage(state.languageKey || 'english');
    dashboardName.textContent = state.displayName?.trim().split(/\s+/)[0] || (state.languageKey === 'hindi' ? 'नमस्ते' : 'there');
    dashboardLanguage.textContent = state.language || 'English';
    document.querySelector('#more-name').textContent = state.displayName || 'SUTRA user';
    document.querySelector('#more-language').textContent = state.language || 'English';
    document.querySelector('#profile-avatar').textContent = (state.displayName || 'S').trim().slice(0, 1).toUpperCase();
    document.querySelector('#connection-label').textContent = state.connectionText || (state.languageKey === 'hindi' ? 'टीम को खोज रहे हैं' : 'Looking for your team');
    const warning = document.querySelector('#speech-warning');
    warning.hidden = !!state.speechReady;
    warning.textContent = state.speechIssue || '';
    document.querySelector('#talk-peer-count').textContent = `${state.connectionCount || 0} online`;
    document.querySelector('#nearby-count').textContent = `${state.connectionCount || 0} online`;
    const nearbyStrings = uiStrings[state.languageKey] || {};
    document.querySelector('#nearby-description').textContent = state.connectionCount
      ? `${state.connectionCount} ${state.languageKey === 'hindi' ? 'टीम सदस्य आस-पास कनेक्ट हैं।' : 'SUTRA teammates connected nearby.'}`
      : nearbyStrings.nearbyDescription || 'Nearby teammates appear when their SUTRA phones connect.';
    addPeerRows(document.querySelector('#talk-peers-list'), state.peers || []);
    addPeerRows(document.querySelector('#nearby-list'), state.peers || []);
    renderMap(state);
    renderMessages(state.messages || []);
    const record = document.querySelector('#record-button');
    record.disabled = !state.speechReady;
    document.querySelector('#handsfree-button').disabled = !state.speechReady;
    record.classList.toggle('is-listening', !!state.recording);
    recordCaption.textContent = state.recording ? (state.languageKey === 'hindi' ? 'सुन रहे हैं…' : 'Listening…') : (state.languageKey === 'hindi' ? 'बोलने के लिए दबाएँ' : 'Hold to talk');
    document.querySelector('#handsfree-button').classList.toggle('is-active', !!state.handsFree);
    document.querySelector('#emergency-button').classList.toggle('is-active', !!state.alertMode);
    if (languageGrid.childElementCount === 0 && state.languages) renderLanguageOptions(state.languages);
    if (state.needsOnboarding) {
      onboarding.classList.remove('is-hidden'); appView.classList.add('is-hidden');
    } else {
      onboarding.classList.add('is-hidden'); appView.classList.remove('is-hidden');
      document.body.classList.add('app-active');
      if (!appShown) {
        document.querySelector('#talk').scrollTop = 0;
        window.scrollTo(0, 0);
        appShown = true;
      }
    }
  };
  window.sutraOnboardingFinished = () => {
    onboarding.classList.add('is-hidden'); appView.classList.remove('is-hidden');
    document.body.classList.add('app-active');
    document.querySelector('#talk').scrollTop = 0;
    window.scrollTo(0, 0);
    appShown = true;
  };
  window.sutraShowOnboarding = () => {
    step = 0; render();
    appShown = false;
    if (nativeState) {
      nameInput.value = nativeState.displayName || '';
      setLanguage(nativeState.languageKey || 'english');
    }
    appView.classList.add('is-hidden'); onboarding.classList.remove('is-hidden');
    document.body.classList.remove('app-active');
  };

  phoneStage.addEventListener('pointermove', event => {
    const rect = phoneStage.getBoundingClientRect();
    const x = (event.clientX - rect.left) / rect.width - .5;
    const y = (event.clientY - rect.top) / rect.height - .5;
    phoneStage.style.transform = `rotate(${6 + y * -4}deg) rotateY(${-10 + x * 12}deg) translateY(-3px)`;
  });
  phoneStage.addEventListener('pointerleave', () => { phoneStage.style.transform = 'rotate(6deg) rotateY(-10deg)'; });
  render();
  if (window.SutraAndroid) {
    document.body.classList.add('native-app');
    try { window.sutraRenderState(JSON.parse(window.SutraAndroid.state())); } catch (_) {}
    setInterval(() => {
      try { window.sutraRenderState(JSON.parse(window.SutraAndroid.state())); } catch (_) {}
    }, 1500);
  } else {
    renderLanguageOptions([
      { key: 'english', nativeName: 'English', englishName: 'English' },
      { key: 'hindi', nativeName: 'हिन्दी', englishName: 'Hindi' },
      { key: 'bengali', nativeName: 'বাংলা', englishName: 'Bengali' },
      { key: 'marathi', nativeName: 'मराठी', englishName: 'Marathi' },
    ]);
  }
})();
