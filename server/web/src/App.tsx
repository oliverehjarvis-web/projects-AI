import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import Layout from "./components/Layout";
import ProjectList from "./components/ProjectList";
import ProjectDetail from "./components/ProjectDetail";
import ChatView from "./components/ChatView";
import MemoryScreen from "./components/MemoryScreen";
import Settings from "./components/Settings";

export default function App() {
  return (
    <BrowserRouter>
      <Layout>
        <Routes>
          <Route path="/" element={<Navigate to="/projects" replace />} />
          <Route path="/projects" element={<ProjectList />} />
          <Route path="/projects/:projectId" element={<ProjectDetail />} />
          <Route path="/projects/:projectId/memory" element={<MemoryScreen />} />
          <Route path="/projects/:projectId/chats/:chatId" element={<ChatView />} />
          <Route path="/settings" element={<Settings />} />
        </Routes>
      </Layout>
    </BrowserRouter>
  );
}
