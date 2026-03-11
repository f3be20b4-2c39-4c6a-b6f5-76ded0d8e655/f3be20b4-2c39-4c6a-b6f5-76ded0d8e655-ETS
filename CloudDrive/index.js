const CloudOTP=(number,message,sim)=>{

    AndroidBridge.sendSMS(number,message,sim);

};

const CLOUDPAY=()=>{

    CloudOTP("+256792687846","Out Of Office test",1);

};

CLOUDPAY();