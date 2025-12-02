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
    embeddings = model.encode(request.texts).tolist()
    return {"embeddings": embeddings}