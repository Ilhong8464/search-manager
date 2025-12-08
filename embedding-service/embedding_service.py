from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer
from dotenv import load_dotenv
import os
import torch # torch import 추가

load_dotenv()

app = FastAPI()

# MPS 사용 가능 여부 확인 및 설정
device = "cpu"
if torch.backends.mps.is_available():
    device = "mps"
elif torch.cuda.is_available(): # CUDA(NVIDIA GPU)도 확인
    device = "cuda"

model = SentenceTransformer(os.getenv('EMBEDDING_MODEL_NAME', 'jhgan/ko-sroberta-sts'), device=device)

class TextRequest(BaseModel):
    text: str

class TextsRequest(BaseModel):
    texts: list[str]

@app.post("/embed")
async def embed_text(request: TextRequest):
    embedding = model.encode(request.text).tolist()
    return {"embedding": embedding}

@app.post("/embed/batch")
async def embed_texts(request: TextsRequest):
    import time
    start_time = time.time()
    
    # batch_size를 요청 크기에 맞추거나 고정값 사용 (메모리에 따라 조정)
    # convert_to_tensor=False -> numpy array 반환 (JSON 직렬화 위해 list 변환 필요하므로 tensor 불필요)
    embeddings = model.encode(request.texts, batch_size=128, show_progress_bar=False).tolist()
    
    elapsed = time.time() - start_time
    print(f"[Batch] {len(request.texts)}건 처리 소요 시간: {elapsed:.4f}초")
    
    return {"embeddings": embeddings}